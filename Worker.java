/**
 * Worker keeps picking up an available or an incomplete task from a bag of
 * tasks. Once all tasks are exhausted, it gets terminated.
 */

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;

import java.io.*;

public class Worker implements Watcher, Closeable {
    private ZooKeeper zk;                       // ZooKeeper to join
    private String hostPort;                    // ZooKeeper's port
    private volatile boolean connected = false; // true if connected to zk
    private volatile boolean expired = false;   // true if session expired
    // worker-000000000d (d=0-9)
    private String workerID = null;             
    private Key key = null;                     // the key for /lock

    /**
     * Is the constructor that accepts ZooKeeper's
     *  IP addr/port to listen at.
     *
     * @param hostPort IP port Zookeeper is listening at.
     */
    public Worker( String hostPort ) {
	    this.hostPort = hostPort;
    }

    /**
     * Joins ZooKeeper session at the port given 
     * through the constructor.
     * The session will be expired at 15 seconds
     *  for no communication.
     */
    public void startZK( ) throws IOException {
        zk = new ZooKeeper( hostPort, 15000, this );
        // creates a key to lock /lock znode.
        key = new Key( zk ); 
    }

    /**
     * Implements Watcher.process( )
     */
    public void process( WatchedEvent e ) { 
        System.out.println( e.toString( ) + ", " + hostPort );
        
        if( e.getType( ) == Event.EventType.None ) {
            switch ( e.getState( ) ) {
                case SyncConnected:
                    /*
                    * Registered with ZooKeeper
                    */
                    connected = true;
                    break;
                case Disconnected:
                    connected = false;
                    break;
                case Expired:
                    expired = true;
                    connected = false;
                    System.err.println( "Session expired" );
                default:
                    break;
            }
        }
    }

    /**
     * Implements Closeable.close( )
     */
    @Override
    public void close( ) 
            throws IOException
    {
        System.out.println( "Closing" );
        try{
            zk.close();
        } catch (InterruptedException e) {
            System.err.println( "ZooKeeper interrupted"
             + "while closing" );
        }
    }

    /**
     * Checks if this worker is connected to ZooKeeper
     *
     * @return true if connected
     */
    public boolean isConnected( ) {
        return connected;
    }

    /**
     * Checks if this worker's session was expired
     *
     * @return true if expired
     */
    public boolean isExpired( ) {
        return expired;
    }

    /**
     * Is Worker's main logic.
     *
     * @param args[] args[0] is Zookeeper's IPaddr:IPport.
     */
    public static void main( String args[] ) throws Exception {
	    // memorize the ZooKeeper port
        Worker worker = new Worker( args[0] );

        // start ZooKeeper
        worker.startZK( );

        // wait until connected to ZooKeeper
        System.out.println( "wait for connection" );
       
        while( !worker.isConnected( ) ) {
                Thread.sleep( 100 );
        }
        System.out.println( "connected" );

        // register my name under /workers
        worker.register( );

        // fall into a task processing cycle.
        for ( String taskID = null;
            ( taskID = worker.pickupTask( ) ) != null; ) {
            /**
             * taskID should be "task-000000000d" 
             * where d = 0-9 if available.
             * otherwise "job stalled" that indicates 
             * a potential worker crash.
             *  */ 
            if ( taskID.equals( "job stalled" ) ) {
                Thread.sleep( 10000 );
                continue;
            }
            System.out.println( taskID + " in progress by " 
                + worker.getID( ) );

            // run the task and remove it from /task znode.
            worker.runTask( taskID );
            worker.finishTask( taskID );
        }

    }

    /**
     * Returns this worker's ID: worker-000000000d
     *  (where d = 0-9)
     *
     * @return this worker's ID.
     */
    private String getID( ) {
	    return workerID;
    }

    //// Implement all methods below ///////////
    /**
     * This is your homework assignment
     *
     * Registers this worker under /workers znode. 
     * The worker should be identified as 
     * /workers/worker-000000000d where d=0-9. It's
     * ephemral and stored in workerID.
     */
    private void register( ) throws Exception {
        workerID = zk.create( "/workers/worker-",
                    null,
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL );
        System.out.println( workerID + " registered" );
    }

    /**
     * This is your homework assignment.
     * Gets a list of tasks from /tasks, checks each task-000000000d where
     * d=0-9, picks up one if its data is "submitted", otherwise examins
     * if its timestamp (i.e., data) gets expired beyond 100 seconds, and 
     * if so picks it up as updating its timestamp to the present. This 
     * method returns task-000000000d as a task ID to execute or "job
     * stalled" if all remaining tasks are being 
     * executed below 100 seconds.
     * If no more tasks are found under /tasks, the method returns null.
     *
     * @returns task-00000000d where d=0-9, as a taskID to execute.
     */
    private String pickupTask( ) {
        boolean jobStalled = false;
        
        /**
         * acquire lock , so this worker has 
         * exclusive access to tasks */ 
        key.lock();
        try {
            List<String> children
            = zk.getChildren( "/tasks", taskWatcher, null );
            for ( int i = 0; children != null && 
                i < children.size( ); i++) {
                System.out.println( children.get( i ) );
                
                Stat taskStat = new Stat( );
                String taskStatus 
                    = new String( zk.getData( "/tasks/" + children.get(i),
                                false, taskStat ) );
                System.out.println( taskStatus + "'s version: " +
                        taskStat.getVersion( ) );
                if ( taskStatus.equals( "submitted" ) ) {
                    // get this task
                    Long currTime = new Long( System.currentTimeMillis());

                    // call zk.setData( ) to write currTime.
                    

                    zk.setData( "/tasks/" + children.get( i ),
                            new Long( currTime ).toString( ).getBytes( ),
                                    taskStat.getVersion( ) );
                    /**call zk.getData( ) and its resuults 
                     * should be saved in taskStatus */ 
                   taskStatus
                    = new String( zk.getData( "/tasks/" + children.get(i),
                                              false, taskStat ) );
                    System.out.println( "taskStatus in if " + taskStatus);

                    // release the lock
                    key.unlock();

                    return children.get( i );
                }
                else {
                    // check if this task is overdue.
                    // get the current time into currTime.
                    Long currTime = new Long( System.currentTimeMillis());

                    //get the submitted time into pastTime.

                    Long pastTime = Long.parseLong(taskStatus);

                    Long diff = currTime - pastTime; 

                    // compute diff of times
                    System.out.println( "currTime = " + currTime +
                        ", pastTime = " + pastTime +
                        ", diff = " + diff );
                    if ( diff > 100000 ) { // if overdue
                        System.out.println( "overdue" );
                        
                        //zk.setData( ) to write currTime
                        zk.setData( "/tasks/" + children.get( i ),
                            new Long( currTime ).toString( ).getBytes( ),
                                    taskStat.getVersion( ) );
                        
                        /**call zk.getData( ) and its resuults 
                        * should be saved in taskStatus */
                        taskStatus = new String( zk.getData( "/tasks/" 
                        + children.get( i ),false, taskStat ) );          
                        System.out.println( "taskStatus in else " 
                            + taskStatus );
                        
                        // release the lock
                        key.unlock();
                        
                        return children.get( i );
                    }
                    else {
                        jobStalled = true;
                    }
                }
            }
        } catch( Exception e ) {
            System.err.println( e.toString( ) );
        }
        // release the lock
        key.unlock();

        System.out.println("jobStalled " + jobStalled);
        return ( jobStalled ) ? "job stalled" : null;
    }

    /**
     * Watches any changes of /tasks. 
     * Just prints out an incoming watch event
     */
    Watcher taskWatcher = new Watcher( ) {
        public void process( WatchedEvent e ) {
            System.out.println( e.toString( ) );
        }
    };

    /**
     * This is your homework assignment.
     *
     * Receives a taskID, (i.e., task-00000000d 
     * where d = 0-9), converts it
     * to 500,000 - 5,0000,000 vertices, and runs:<br>
     * java -Xss512m GraphBridge vertices
     *
     * @param taskID a task obtained from the bag of tasks, 
     * from which the worker runs "java -Xss512m 
     * GraphBridge (taskID + 1) * 1000000/2
     */
    private void runTask( String taskID ) throws Exception {
        // compute the number of vertices from taskID
        int vertices
            = ( Integer.parseInt(taskID.split("-")[1]) + 1) * 1000000/2;
        System.out.println( "vertices = " + vertices );

        // creates a task array
        String[] args
            = { "java", "-Xss512m", "GraphBridge",
            ( new Integer( vertices ) ).toString( ) };

        // Launch a new process for the task
        Process process = Runtime.getRuntime().exec(args);

        // Retrieve the process's input stream
        InputStream inputStream = process.getInputStream();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream));

        for ( String line = null; 
            ( line = reader.readLine( ) ) != null; ){
             // keep writing the outputs to stdout.
            System.out.println( line );
        }

        //wait for the termination of this task and exit

        int exitCode = process.waitFor();
        System.out.println("Task:  " + taskID 
            + " terminated with exit code: " + exitCode);
    }

    /**
     * This is your homework assignment.
     *
     * Declares a completion of a given task
     * @param taskID the ID of a task to be completed
     */
    private void finishTask( String taskID ) throws Exception {
        // get the latest version of this taskID
        Stat taskStat = new Stat( );
        
        //call zk.getData( ) to get its taskID's state into taskStat
        String taskPath = "/tasks/" + taskID;

        String taskStatus = new String( zk.getData( taskPath,
                                              false, taskStat ) ); 
        // Delete this task by passing the up-to-date version
        int version = taskStat.getVersion();
        zk.delete(taskPath, version);   
    }
}
