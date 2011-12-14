/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha.zookeeper;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.HaConfig;
import org.neo4j.kernel.ha.ConnectionInformation;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.ResponseReceiver;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.transaction.xaframework.TxIdGenerator;
import org.neo4j.kernel.impl.util.StringLogger;

import javax.management.remote.JMXServiceURL;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.neo4j.kernel.ha.zookeeper.ClusterManager.getSingleRootPath;

public class ZooClient extends AbstractZooKeeperManager
{
    static final String MASTER_NOTIFY_CHILD = "master-notify";
    static final String MASTER_REBOUND_CHILD = "master-rebound";

    private ZooKeeper zooKeeper;
    private final int machineId;
    private String sequenceNr;

    private long committedTx;

    private final Object keeperStateMonitor = new Object();
    private volatile KeeperState keeperState = KeeperState.Disconnected;
    private volatile boolean shutdown = false;
    private final RootPathGetter rootPathGetter;
    private String rootPath;
    
    // Has the format <host-name>:<port>
    private final String haServer;

    private final StringLogger msgLog;

    private long sessionId = -1;
    private final ResponseReceiver receiver;
    private final int backupPort;
    private final boolean writeLastCommittedTx;

    public ZooClient( AbstractGraphDatabase graphDb, Map<String, String> config, ResponseReceiver receiver )
    {
        super( HaConfig.getCoordinatorsFromConfig( config ),
            graphDb,
            HaConfig.getClientReadTimeoutFromConfig( config ),
            HaConfig.getClientLockReadTimeoutFromConfig( config ),
            HaConfig.getMaxConcurrentChannelsPerSlaveFromConfig( config ) );
        this.receiver = receiver;
        machineId = HaConfig.getMachineIdFromConfig( config );
        backupPort = HaConfig.getBackupPortFromConfig( config );
        haServer = HaConfig.getHaServerFromConfig( config );
        writeLastCommittedTx = HaConfig.getSlaveUpdateModeFromConfig( config ).syncWithZooKeeper;
        rootPathGetter = getRootPathGetter( graphDb.getStoreDir() );
        sequenceNr = "not initialized yet";
        msgLog = graphDb.getMessageLog();
        zooKeeper = instantiateZooKeeper();
    }

    static RootPathGetter getRootPathGetter( String storeDir )
    {
        try
        {
            new NeoStoreUtil( storeDir );
            return RootPathGetter.forKnownStore( storeDir );
        }
        catch ( RuntimeException e )
        {
            return RootPathGetter.forUnknownStore( storeDir );
        }
    }

    @Override
    protected int getMyMachineId()
    {
        return this.machineId;
    }

    public void process( WatchedEvent event )
    {
        try
        {
            String path = event.getPath();
            msgLog.logMessage( this + ", " + new Date() + " Got event: " + event + " (path=" + path + ")", true );
            if ( path == null && event.getState() == Watcher.Event.KeeperState.Expired )
            {
                keeperState = KeeperState.Expired;
                if ( zooKeeper != null )
                {
                    try
                    {
                        zooKeeper.close();
                    }
                    catch ( InterruptedException e )
                    {
                        e.printStackTrace();
                        Thread.interrupted();
                    }
                }
                zooKeeper = instantiateZooKeeper();
            }
            else if ( path == null && event.getState() == Watcher.Event.KeeperState.SyncConnected )
            {
                long newSessionId = zooKeeper.getSessionId();
                Pair<Master, Machine> masterBeforeIWrite = getMasterFromZooKeeper( false, false );
                msgLog.logMessage( "Get master before write:" + masterBeforeIWrite );
                boolean masterBeforeIWriteDiffers = masterBeforeIWrite.other().getMachineId() != getCachedMaster().other().getMachineId();
                if ( newSessionId != sessionId || masterBeforeIWriteDiffers )
                {
                    if ( writeLastCommittedTx )
                    {
                        sequenceNr = setup();
                        msgLog.logMessage( "Did setup, seq=" + sequenceNr + " new sessionId=" + newSessionId );
                        Pair<Master, Machine> masterAfterIWrote = getMasterFromZooKeeper( false, false );
                        msgLog.logMessage( "Get master after write:" + masterAfterIWrote );
                        int masterId = masterAfterIWrote.other().getMachineId();
//                        setDataChangeWatcher( MASTER_NOTIFY_CHILD, masterId );
                        msgLog.logMessage( "Set '" + MASTER_NOTIFY_CHILD + "' to " + masterId );
                        if ( sessionId != -1 )
                        {
                            receiver.newMaster( new Exception( "Got SyncConnected event from ZK" ) );
                        }
                        sessionId = newSessionId;
                    }
                    else
                    {
                        msgLog.logMessage( "Didn't do setup due to told not to write" );
                        keeperState = KeeperState.SyncConnected;
                        subscribeToDataChangeWatcher( MASTER_REBOUND_CHILD );
                    }
                    keeperState = KeeperState.SyncConnected;
                }
                else
                {
                    msgLog.logMessage( "SyncConnected with same session id: " + sessionId );
                    keeperState = KeeperState.SyncConnected;
                }
            }
            else if ( path == null && event.getState() == Watcher.Event.KeeperState.Disconnected )
            {
                keeperState = KeeperState.Disconnected;
            }
            else if ( event.getType() == Watcher.Event.EventType.NodeDataChanged )
            {
                int newMasterMachineId = toInt( getZooKeeper().getData( path, true, null ) );
                if ( path.contains( MASTER_NOTIFY_CHILD ) )
                {
                    // This event is for the masters eyes only so it should only
                    // be the (by zookeeper spoken) master which should make sure
                    // it really is master.
                    if ( newMasterMachineId == machineId )
                    {
                        receiver.newMaster( new Exception() );
                    }
                }
                else if ( path.contains( MASTER_REBOUND_CHILD ) )
                {
                    // This event is for all the others after the master got the
                    // MASTER_NOTIFY_CHILD which then shouts out to the others to
                    // become slaves if they don't already are.
                    if ( newMasterMachineId != machineId )
                    {
                        receiver.newMaster( new Exception() );
                    }
                }
                else
                {
                    msgLog.logMessage( "Unrecognized data change " + path );
                }
            }
        }
        catch ( Exception e )
        {
            msgLog.logMessage( "Error in ZooClient.process", e, true );
            e.printStackTrace();
            throw Exceptions.launderedException( e );
        }
        finally
        {
            msgLog.flush();
        }
    }

    private int toInt( byte[] data )
    {
        return ByteBuffer.wrap( data ).getInt();
    }

    @Override
    public void waitForSyncConnected()
    {
        if ( keeperState == KeeperState.SyncConnected )
        {
            return;
        }
        if ( shutdown == true )
        {
            throw new ZooKeeperException( "ZooKeeper client has been shutdwon" );
        }
        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        synchronized ( keeperStateMonitor )
        {
            do
            {
                try
                {
                    keeperStateMonitor.wait( 250 );
                }
                catch ( InterruptedException e )
                {
                    Thread.interrupted();
                }
                if ( keeperState == KeeperState.SyncConnected )
                {
                    return;
                }
                if ( shutdown == true )
                {
                    throw new ZooKeeperException( "ZooKeeper client has been shutdwon" );
                }
                currentTime = System.currentTimeMillis();
            }
            while ( (currentTime - startTime) < SESSION_TIME_OUT );

            if ( keeperState != KeeperState.SyncConnected )
            {
                throw new ZooKeeperTimedOutException(
                        "Connection to ZooKeeper server timed out, keeper state=" + keeperState );
            }
        }
    }

    protected void subscribeToDataChangeWatcher( String child )
    {
        String root = getRoot();
        String path = root + "/" + child;
        try
        {
            zooKeeper.getData( path, true, null );
        }
        catch ( KeeperException e )
        {
            msgLog.logMessage( "Couldn't get master notify node", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted", e );
        }
    }
    
    protected void setDataChangeWatcher( String child, int currentMasterId )
    {
        try
        {
            String root = getRoot();
            String path = root + "/" + child;
            byte[] data = null;
            boolean exists = false;
            try
            {
                data = zooKeeper.getData( path, true, null );
                exists = true;
                
                if ( ByteBuffer.wrap( data ).getInt() == currentMasterId )
                {
                    msgLog.logMessage( child + " not set, is already " + currentMasterId );
                    return;
                }
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NONODE )
                {
                    throw new ZooKeeperException( "Couldn't get master notify node", e );
                }
            }

            // Didn't exist or has changed
            try
            {
                data = new byte[4];
                ByteBuffer.wrap( data ).putInt( currentMasterId );
                if ( !exists )
                {
                    zooKeeper.create( path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT );
                    msgLog.logMessage( child + " created with " + currentMasterId );
                }
                else if ( currentMasterId != -1 )
                {
                    zooKeeper.setData( path, data, -1 );
                    msgLog.logMessage( child + " set to " + currentMasterId );
                }

                // Add a watch for it
                zooKeeper.getData( path, true, null );
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NODEEXISTS )
                {
                    throw new ZooKeeperException( "Couldn't set master notify node", e );
                }
            }
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted", e );
        }
    }

    @Override
    public String getRoot()
    {
        makeSureRootPathIsFound();

        // Make sure it exists
        byte[] rootData = null;
        do
        {
            try
            {
                rootData = zooKeeper.getData( rootPath, false, null );
                return rootPath;
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NONODE )
                {
                    throw new ZooKeeperException( "Unable to get root node",
                        e );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
            // try create root
            if ( getSingleRootPath( zooKeeper ) != null ) throw new RuntimeException( "There's already an HA cluster managed by this ZooKeeper cluster" );
            try
            {
                byte data[] = new byte[0];
                zooKeeper.create( rootPath, data, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT );
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NODEEXISTS )
                {
                    throw new ZooKeeperException( "Unable to create root", e );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
        } while ( rootData == null );
        throw new IllegalStateException();
    }

    private void makeSureRootPathIsFound()
    {
        if ( rootPath == null )
        {
            Pair<String, Long> info = rootPathGetter.getRootPath( zooKeeper );
            rootPath = info.first();
            committedTx = info.other();
        }
    }

    private void cleanupChildren()
    {
        try
        {
            String root = getRoot();
            List<String> children = zooKeeper.getChildren( root, false );
            for ( String child : children )
            {
                Pair<Integer, Integer> parsedChild = parseChild( child );
                if ( parsedChild == null )
                {
                    continue;
                }
                if ( parsedChild.first() == machineId )
                {
                    zooKeeper.delete( root + "/" + child, -1 );
                }
            }
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to clean up old child", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted.", e );
        }
    }
    
    private int getCurrentMasterId()
    {
        try
        {
            TxIdGenerator generator = (TxIdGenerator) ((AbstractGraphDatabase)this.getGraphDb()).getConfig().getParams().get( TxIdGenerator.class );
            return generator.getCurrentMasterId();
        }
        catch ( Exception e )
        {
            // This will happen if the graph database hasn't started yet
            return -1;
        }
    }

    private byte[] dataRepresentingMe( long txId )
    {
        byte[] array = new byte[12];
        ByteBuffer buffer = ByteBuffer.wrap( array );
        buffer.putLong( txId );
        buffer.putInt( getCurrentMasterId() );
        return array;
    }

    private String setup()
    {
        try
        {
            cleanupChildren();
            writeHaServerConfig();
            String root = getRoot();
            String path = root + "/" + machineId + "_";
            String created = zooKeeper.create( path, dataRepresentingMe( committedTx ),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL );

            // Add watches to our master notification nodes
            subscribeToDataChangeWatcher( MASTER_NOTIFY_CHILD );
            subscribeToDataChangeWatcher( MASTER_REBOUND_CHILD );
            return created.substring( created.lastIndexOf( "_" ) + 1 );
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to setup", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Setup got interrupted", e );
        }
        catch ( Throwable t )
        {
            throw new ZooKeeperException( "Unknown setup error", t );
        }
    }

    private void writeHaServerConfig() throws InterruptedException, KeeperException
    {
        // Make sure the HA server root is created
        String path = rootPath + "/" + HA_SERVERS_CHILD;
        try
        {
            zooKeeper.create( path, new byte[0],
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT );
        }
        catch ( KeeperException e )
        {
            if ( e.code() != KeeperException.Code.NODEEXISTS )
            {
                throw e;
            }
        }

        // Write the HA server config.
        String machinePath = path + "/" + machineId;
        byte[] data = haServerAsData();
        try
        {
            zooKeeper.create( machinePath, data,
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL );
        }
        catch ( KeeperException e )
        {
            if ( e.code() != KeeperException.Code.NODEEXISTS )
            {
                throw e;
            }
            msgLog.logMessage( "HA server info already present, trying again" );
            try
            {
                zooKeeper.delete( machinePath, -1 );
            }
            catch ( KeeperException ee )
            {
                ee.printStackTrace();
                // ok
            }
            finally
            {
                writeHaServerConfig();
            }
        }
        zooKeeper.setData( machinePath, data, -1 );
        msgLog.logMessage( "Wrote HA server " + haServer + " to zoo keeper" );
    }

    private byte[] haServerAsData()
    {
        byte[] array = new byte[haServer.length()*2 + 100];
        ByteBuffer buffer = ByteBuffer.wrap( array );
        buffer.putInt( backupPort );
        buffer.put( (byte) haServer.length() );
        buffer.asCharBuffer().put( haServer.toCharArray() ).flip();
        byte[] actualArray = new byte[buffer.limit()];
        System.arraycopy( array, 0, actualArray, 0, actualArray.length );
        return actualArray;
    }

    public synchronized void setJmxConnectionData( JMXServiceURL jmxUrl, String instanceId )
    {
        String path = rootPath + "/" + HA_SERVERS_CHILD + "/" + machineId + "-jmx";
        String url = jmxUrl.toString();
        byte[] data = new byte[( url.length() + instanceId.length() ) * 2 + 4];
        ByteBuffer buffer = ByteBuffer.wrap( data );
        // write URL
        buffer.putShort( (short) url.length() );
        buffer.asCharBuffer().put( url.toCharArray() );
        buffer.position( buffer.position() + url.length() * 2 );
        // write instanceId
        buffer.putShort( (short) instanceId.length() );
        buffer.asCharBuffer().put( instanceId.toCharArray() );
        // truncate array
        if ( buffer.limit() != data.length )
        {
            byte[] array = new byte[buffer.limit()];
            System.arraycopy( data, 0, array, 0, array.length );
            data = array;
        }
        try
        {
            try
            {
                zooKeeper.setData( path, data, -1 );
            }
            catch ( KeeperException e )
            {
                if ( e.code() == KeeperException.Code.NONODE )
                {
                    zooKeeper.create( path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL );
                }
                else
                {
                    msgLog.logMessage( "Unable to set jxm connection info", e );
                }
            }
        }
        catch ( KeeperException e )
        {
            msgLog.logMessage( "Unable to set jxm connection info", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            msgLog.logMessage( "Unable to set jxm connection info", e );
        }
    }

    public void getJmxConnectionData( ConnectionInformation connection )
    {
        String path = rootPath + "/" + HA_SERVERS_CHILD + "/" + machineId + "-jmx";
        byte[] data;
        try
        {
            data = zooKeeper.getData( path, false, null );
        }
        catch ( KeeperException e )
        {
            return;
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            return;
        }
        if ( data == null || data.length == 0 ) return;
        ByteBuffer buffer = ByteBuffer.wrap( data );
        char[] url, instanceId;
        try
        {
            // read URL
            url = new char[buffer.getShort()];
            buffer.asCharBuffer().get( url );
            buffer.position( buffer.position() + url.length * 2 );
            // read instanceId
            instanceId = new char[buffer.getShort()];
            buffer.asCharBuffer().get( instanceId );
        }
        catch ( BufferUnderflowException e )
        {
            return;
        }
        connection.setJMXConnectionData( new String( url ), new String( instanceId ) );
    }

    public synchronized void setCommittedTx( long tx )
    {
//        msgLog.logMessage( "ZooClient setting txId=" + tx + " for machine=" + machineId, true );
        waitForSyncConnected();
        this.committedTx = tx;
        String root = getRoot();
        String path = root + "/" + machineId + "_" + sequenceNr;
        byte[] data = dataRepresentingMe( tx );
        try
        {
            zooKeeper.setData( path, data, -1 );
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to set current tx", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted...", e );
        }
    }

    @Override
    public void shutdown()
    {
        this.shutdown = true;
        super.shutdown();
    }

    @Override
    protected ZooKeeper getZooKeeper()
    {
        return zooKeeper;
    }

    @Override
    protected String getHaServer( int machineId, boolean wait )
    {
        return machineId == this.machineId ? haServer : super.getHaServer( machineId, wait );
    }

    public synchronized StoreId createCluster( String clusterName, StoreId storeIdSuggestion )
    {
        String path = "/" + clusterName;
        try
        {
            try
            {
                zooKeeper.create( path, storeIdSuggestion.serialize(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT );
                return storeIdSuggestion; // if successfully written
            }
            catch ( KeeperException e )
            {
                if ( e.code() == KeeperException.Code.NODEEXISTS )
                { // another instance wrote before me
                    try
                    { // read what that instance wrote
                        return StoreId.deserialize( zooKeeper.getData( path, false, null ) );
                    }
                    catch ( KeeperException ex )
                    {
                        throw new ZooKeeperException( "Unable to read cluster store id", ex );
                    }
                }
                else
                {
                    throw new ZooKeeperException( "Unable to write cluster store id", e );
                }
            }
        }
        catch ( InterruptedException e )
        {
            throw new ZooKeeperException( "createCluster interrupted", e );
        }
    }
}