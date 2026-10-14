package dev.adbox.toolkit;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Bounded ordered input: replace obsolete motion, preserving key/down/up boundaries. */
public final class ScreenInputQueue {
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();private final int capacity;private boolean closed;
    public ScreenInputQueue(int capacity){if(capacity<2)throw new IllegalArgumentException("Input capacity must be at least two");this.capacity=capacity;}
    private static boolean move(byte[] bytes){return bytes.length==32&&bytes[0]==2&&bytes[1]==2;}
    public synchronized boolean offer(byte[] bytes){
        if(closed)return false;
        if(move(bytes)&&!queue.isEmpty()&&move(queue.peekLast())){queue.removeLast();queue.addLast(bytes.clone());notifyAll();return true;}
        if(queue.size()>=capacity){
            for(Iterator<byte[]> i=queue.iterator();i.hasNext();)if(move(i.next())){i.remove();break;}
            if(queue.size()>=capacity)return false;
        }
        queue.addLast(bytes.clone());notifyAll();return true;
    }
    public synchronized byte[] take()throws InterruptedException {while(queue.isEmpty()&&!closed)wait();return closed?null:queue.removeFirst();}
    public synchronized void close(){closed=true;queue.clear();notifyAll();}
    public synchronized int size(){return queue.size();}
}
