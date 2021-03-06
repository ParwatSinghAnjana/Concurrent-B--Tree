/*
 * Concurrent B*-Tree implementation for the 
 * Concurrent Search Tree Project for
 * Parallel Computing I
 *
 * Author: David C. Larsen <dcl9934@cs.rit.edu>
 * Date: April. 12, 2011
 */

import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.lang.reflect.Array;

public class BTreeSMP<K extends Comparable,V> implements BTree<K,V>, Runnable
{
    private int size = 0;
    ReentrantLock lock;

    private Node<K,V> root = null;
    private BlockingQueue<Pair<K,V>> addQueue = null;
    private boolean terminate = false;
    private Thread putHandler = null;

    /**
     * Constructs a new SMP BTree.
     */
    public BTreeSMP() {
        addQueue = (BlockingQueue<Pair<K,V>>)new LinkedBlockingQueue<Pair<K,V>>();
        putHandler = new Thread( this );
        putHandler.start();
        lock = new ReentrantLock();
    }

    /**
     * This method acts as a master-type entity and coordinates insertions into
     * the tree. This allows a process to quickly insert a value into the tree
     * then resume work before it is even inserted.
     *
     * The problem with this however is that elements recently inserted into
     * the tree may not necessarily be there immediately afterwards for access.
     *
     * This turns out to not be a huge issue because a thread inserting an
     * element into the tree will not be taking it from the tree immediately
     * afterwards AND most insertions will occur immediately anyway unless if
     * the put queue is getting blasted with requests.
     */
    public void run() {
        while( !terminate ) {
            try {
                Pair<K,V> p = addQueue.poll(100, TimeUnit.MILLISECONDS );
                // the use of a boolean here relies on the overservation that any
                // parallel access of the tree will take longer than a simple
                // search because a search must first be performed.

                // while not inherently threadsafe, we should not see odd behavior
                if( p != null ) {
                    lock.lock();
                    actualPut( p.left(), p.right() );
                    while( !addQueue.isEmpty() ) {
                        p = addQueue.remove();
                        actualPut(p.left(), p.right());
                    }
                    lock.unlock();
                } 
            } catch( java.lang.InterruptedException e) {
                // the thread shouldn't be interrupted
                e.printStackTrace();
                terminate = true;
            }
        }
        if( !isSearchable() ) {
            lock.unlock();
        }
        // we want waitng threads to exit out of their wait and complete
    }

    public void terminate() {
        terminate = true;
    }

    /** {@inheritDoc} */
    public void clear()
    {
        // We'll let the garbage collector worry about it.
        root = null;
    }

    /** {@inheritDoc} */
    public boolean containsKey( K key )
    {
        return get( key ) != null;
    }

    /** {@inheritDoc} */
    public boolean containsValue( V value )
    {
        assert(false);
        return false;
    }

    public boolean isSearchable() {
        return !lock.isLocked();
    }
    /** {@inheritDoc} */
    public V get( K key )
    {
        while( !isSearchable() );
        Node<K,V> currentNode = root;

        while( currentNode instanceof InternalNode )
        {
            currentNode = currentNode.getChild(key).left();
        }
        if( currentNode instanceof LeafNode ) {
            return currentNode.getChild(key).right();
        } else {
            return null;
        }
    }

    /** {@inheritDoc} */
    public boolean isEmpty()
    {
        return size == 0;
    }

    /** {@inheritDoc} */
    public V put( K key, V value )
    {
        V old = get( key );
        addQueue.offer( new Pair<K,V>(key, value) );
        return old;
    }

    /**
     * The put method used by the master thread to actually perform insertions.
     *
     * @param key The key of the value to insert.
     * @param value The value of the key to insert.
     */
    protected void actualPut( K key, V value ) {
        // find the leaf node that would contain this value
        Node<K,V> currentNode = root;
        while( currentNode instanceof InternalNode ) {
            Node<K,V> newNode = currentNode.getChild(key).left();
            currentNode = newNode;
        }

        LeafNode<K,V> leaf = (LeafNode<K,V>)currentNode; 
        if( currentNode != null ) {
            // save the current node

            // can we fit the new value into this node?
            if( !leaf.addValue( key, value ) ) {
                // We have to split the node
                LeafNode<K,V> right = leaf.split(key, value).right();
                Node<K,V> newRight = right;
                // we need to add the new node to the parent node, we then need to repeat this process.
                InternalNode<K,V> parent = (InternalNode<K,V>)right.parent;

                // loop until we reach the root node or we are successfully able to add a child node
                K addToParent = newRight.lowerBound();
                while( parent != null ) {
                    if( parent.addChild(addToParent, newRight) ) {
                        break;
                    }
                    // split the parent node
                    InternalNode<K,V> parentRight =  (InternalNode<K,V>)parent.split(addToParent, newRight).left();
                    K addToParentNew = parent.getMiddleKey();

                    // update the parent and the right node
                    addToParent = addToParentNew;
                    InternalNode<K,V> newParent = (InternalNode<K,V>)parent.parent;
                    parent = newParent;
                    newRight = parentRight;
                }

                // The root has been split, we need to create a new root.
                if( parent == null ) {
                    Node<K,V> newRoot = new InternalNode<K,V>( root, newRight,addToParent );
                    root.parent = newRoot;
                    newRight.parent = newRoot;
                    root = newRoot;
                }
            }
        } else {    // There isn't a root node yet
            root = new LeafNode<K,V>( key, value );
        }
    }

    /** {@inheritDoc} */
    public V remove( K key )
    {
        // Temporary solution: Mark the value for deletion by setting its 
        // value to null.
        //
        // Despite temporary-ness of the solution this IS a viable solution for
        // large trees because deletion may require mass restructuring of the 
        // whole tree!
        V oldVal = get( key );
        if( oldVal != null ) {
            put( key, null );
        }

        return oldVal;
    }

    /** {@inheritDoc} */
    public int size()
    {
        return this.size;
    }

    /**
     * Obtains a(n admittedly hard to read) String representation of the BTree.
     *
     * @return A String representation of the tree.
     */
    public String toString()
    {
        if( root != null )
        {
            return root.toString();
        }
        else
        {
            return null;
        }
    }

    /** {@inheritDoc} */
    public Node<K,V> getRoot() {
        return root;
    }
}
