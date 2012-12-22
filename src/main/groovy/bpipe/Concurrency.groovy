/*
 * Copyright (c) 2012 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import groovy.util.logging.Log;


/**
 * A resource that can be managed by Bpipe.
 * The key is the name of the resource (eg: "memory") 
 * and the amount is the amount that is being used by  particular
 * operation.
 * 
 * @author ssadedin
 */
@Log
class ResourceUnit {
    
    int amount = 0;
    
    String key
}

@Singleton
@Log
/**
 * Manages concurrency for parallel pipelines.
 * <p>
 * This class is responsible for managing a thread pool that is configured
 * with size according to the maximum concurrency specified by the user
 * on the command line (-n option), and it also handles execution of groups
 * of tasks as a unit so that they can all be entered into the common thread
 * pool and the flow returns only when they all are completed.
 * <p>
 * There are two layers of concurrency management implemented. The first is the
 * raw capacity of the thread pool. This ensures that absolute concurrency within
 * Bpipe can't exceed the user's configuration.  However there is a second, logical
 * level of concurrency that is enforced on top of that, using a global semaphore
 * that is acquired/released as each parallel segment runs. The purpose of this
 * second "logical" level is that it allows a user to reserve more than n=1 concurrency
 * for a single thread if that thread will create particularly heavy load. The 
 * obvious situation where that happens is if the thread itself launches child threads
 * that are outside of Bpipe's control, or if it runs (shell) commands that themeselves
 * launch multiple threads. In these cases the "logical" concurrency control can 
 * be used to restrict the actual concurrency below that enforced by the physical
 * thread pool to manage the actual load generated by the pipeline.
 */
class Concurrency {
    
    /**
     * The thread pool to use for executing tasks.
     */
    ThreadPoolExecutor pool 
    
    /**
     * Each separate parallel path acquires a permit from this semaphore,
     * ensuring that no more than the maximum parallel paths can execute
     * across the whole pipeline
     */
    
//    Semaphore commandConcurrency 
    
    /**
     * Each resource allocation allocates resources for its resource type against
     * these resource allocations.
     */
    Map<String,Semaphore> resourceAllocations = [ threads: new Semaphore(Config.config.maxThreads)]
    
    /**
     * Counts of threads running
     */
    Map<Runnable,AtomicInteger> counts = [:]
    
    Concurrency() {
        
        log.info "Creating thread pool with " + Config.config.maxThreads + " threads to execute parallel pipelines"
        
        ThreadFactory threadFactory = { Runnable r ->
                          def t = new Thread(r)  
                          t.setDaemon(true)
                          return t
                        } as ThreadFactory
        
        this.pool = new ThreadPoolExecutor(Config.config.maxThreads, Integer.MAX_VALUE,
                                      0L, TimeUnit.MILLISECONDS,
//                                      new LinkedBlockingQueue<Runnable>(), 
                                      new SynchronousQueue<Runnable>(), 
                                      threadFactory) {
              @Override
              void afterExecute(Runnable r, Throwable t) {
                  AtomicInteger runningCount
                  synchronized(counts) {
                    runningCount = counts[r]
                  }
                  
                  int value = runningCount.decrementAndGet()
                  
                  log.info "Decremented running count to $value in thread " + Thread.currentThread().name
                  
                  // Notify parent that will be waiting on this count
                  // for each decrement
                  synchronized(runningCount) {
                      runningCount.notify()
                  }
              }
        }
        
//        if(Config.userConfig.maxMemoryMB) {
//            resourceAllocations["memory"] = new Semaphore(Integer.parseInt(Config.userConfig.maxMemoryMB))
//        }               
//        
//        if(Config.config.maxMemoryMB) {
//            resourceAllocations["memory"] = new Semaphore(Config.config.maxMemoryMB)
//        }               
    }
    
    /**
     * Execute the given list of runnables using the global thread pool,
     * and wait for them all to finish. 
     * 
     * @param runnables
     */
    void execute(List<Runnable> runnables) {
        
        AtomicInteger runningCount = new AtomicInteger()
        
        // First set up the count of running pipelines
        for(Runnable r in runnables) {
            synchronized(counts) {
                runningCount.incrementAndGet()
                counts[r] = runningCount
            }
        }
        
        // Now put them in the thread pool
        for(Runnable r in runnables) {
            pool.execute(r); 
        }
            
        // Wait until the count of running threads reaches zero.
        // The count is decremented by the ThreadPoolExecutor#afterExecute
        // call as each thread finishes
        long lastLogTimeMillis = 0
        while(runningCount.get()) {
                
            if(lastLogTimeMillis < System.currentTimeMillis() - 5000) {
                log.info("Waiting for " + runningCount.get() + " parallel stages to complete (pool.active=${pool.activeCount} pool.tasks=${pool.taskCount})" )
                lastLogTimeMillis = System.currentTimeMillis()
            }
                    
            synchronized(runningCount) {
                runningCount.wait(50)
            }
                
            if(runningCount.get())
                Thread.sleep(300)
        }
    }

   /**
    * Called by parallel paths before they begin execution: enforces overall concurrency by blocking
    * the thread before it can start work. (ie. this method may block).
    */
   void acquire(ResourceUnit resourceUnit) {
        Semaphore resource
        synchronized(resourceAllocations) {
            resource = resourceAllocations.get(resourceUnit.key)
        }
        
        if(resource == null) {
            log.info "Unknown resource type $resourceUnit.key specified: treating as infinite resource"
            return
        }
        
       int amount = resourceUnit.amount
        
       log.info "Thread " + Thread.currentThread().getName() + " requesting for $amount concurrency permit(s) type $resourceUnit.key with " + resource.availablePermits() + " available"
       long startTimeMs = System.currentTimeMillis()
       resource.acquire(amount)
       long durationMs = startTimeMs - System.currentTimeMillis()
       if(durationMs > 1000) {
           log.info "Thread " + Thread.currentThread().getName() + " blocked for $durationMs ms waiting for resource $resourceUnit.key amount(s) $amount"
       }
       else
           log.info "Thread " + Thread.currentThread().getName() + " acquired resource $resourceUnit.key in amount $amount"
   }
   
   void release(ResourceUnit resourceUnit) {
        Semaphore resource
        synchronized(resourceAllocations) {
            resource = resourceAllocations.get(resourceUnit.key)
        }
        
        if(resource == null) {
            log.info "Unknown resource type $resourceUnit.key specified: treating as infinite resource"
            return
        }
        
       resource.release(resourceUnit.amount)
       log.info "Thread " + Thread.currentThread().getName() + " releasing $resourceUnit.amount $resourceUnit.key"
   }
   
   void setLimit(String resourceName, int amount) {
       this.resourceAllocations.put(resourceName, new Semaphore(amount))
   }
    
}
