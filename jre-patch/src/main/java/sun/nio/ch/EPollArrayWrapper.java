/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import java.security.AccessController;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import sun.security.action.GetIntegerAction;

/**
 * Manipulates a native array of epoll_event structs on Linux:
 *
 * typedef union epoll_data {
 *     void *ptr;
 *     int fd;
 *     __uint32_t u32;
 *     __uint64_t u64;
 *  } epoll_data_t;
 *
 * struct epoll_event {
 *     __uint32_t events;
 *     epoll_data_t data;
 * };
 *
 * The system call to wait for I/O events is epoll_wait(2). It populates an
 * array of epoll_event structures that are passed to the call. The data
 * member of the epoll_event structure contains the same data as was set
 * when the file descriptor was registered to epoll via epoll_ctl(2). In
 * this implementation we set data.fd to be the file descriptor that we
 * register. That way, we have the file descriptor available when we
 * process the events.
 */

class EPollArrayWrapper {
    private static final int MAX_BUGS = 1000;

    // EPOLL_EVENTS
    private static final int EPOLLIN      = 0x001;

    // opcodes
    private static final int EPOLL_CTL_ADD      = 1;
    private static final int EPOLL_CTL_DEL      = 2;
    private static final int EPOLL_CTL_MOD      = 3;

    // Miscellaneous constants
    private static final int SIZE_EPOLLEVENT  = sizeofEPollEvent();
    private static final int EVENT_OFFSET     = 0;
    private static final int DATA_OFFSET      = offsetofData();
    private static final int FD_OFFSET        = DATA_OFFSET;
    private static final int OPEN_MAX         = IOUtil.fdLimit();
    private static final int NUM_EPOLLEVENTS  = Math.min(OPEN_MAX, 8192);

    // Special value to indicate that an update should be ignored
    private static final byte  KILLED = (byte)-1;

    // Initial size of arrays for fd registration changes
    private static final int INITIAL_PENDING_UPDATE_SIZE = 64;

    // maximum size of updatesLow
    private static final int MAX_UPDATE_ARRAY_SIZE = AccessController.doPrivileged(
            new GetIntegerAction("sun.nio.ch.maxUpdateArraySize", Math.min(OPEN_MAX, 64*1024)));

    private static class EpollData {
        // The fd of the epoll driver
        private final int epfd;

        // The epoll_event array for results from epoll_wait
        private final AllocatedNativeObject pollArray;

        // Base address of the epoll_event array
        private final long pollArrayAddress;

        public EpollData(EPollArrayWrapper owner) {
            // TODO Auto-generated constructor stub
            // creates the epoll file descriptor
            this.epfd = owner.epollCreate();

            // the epoll_event array passed to epoll_wait
            int allocationSize = NUM_EPOLLEVENTS * SIZE_EPOLLEVENT;
            this.pollArray = new AllocatedNativeObject(allocationSize, true);
            this.pollArrayAddress = this.pollArray.address();
        }

        void closeEPollFD() throws IOException {
            FileDispatcherImpl.closeIntFD(this.epfd);
            this.pollArray.free();
        }


        void putEventOps(int i, int event) {
            int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET;
            this.pollArray.putInt(offset, event);
        }

        void putDescriptor(int i, int fd) {
            int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
            this.pollArray.putInt(offset, fd);
        }

        int getEventOps(int i) {
            int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET;
            return this.pollArray.getInt(offset);
        }

        int getDescriptor(int i) {
            int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
            return this.pollArray.getInt(offset);
        }
    }

    private volatile EpollData epollData;

    // The fd of the interrupt line going out
    private int outgoingInterruptFD;

    // The fd of the interrupt line coming in
    private int incomingInterruptFD;

    // The index of the interrupt FD
    private int interruptedIndex;


    // Number of updated pollfd entries
    int updated;

    // object to synchronize fd registration changes
    private final Object updateLock = new Object();

    // number of file descriptors with registration changes pending
    private int updateCount;

    // file descriptors with registration changes pending
    private int[] updateDescriptors = new int[INITIAL_PENDING_UPDATE_SIZE];

    // events for file descriptors with registration changes pending, indexed
    // by file descriptor and stored as bytes for efficiency reasons. For
    // file descriptors higher than MAX_UPDATE_ARRAY_SIZE (unlimited case at
    // least) then the update is stored in a map.
    private final byte[] eventsLow = new byte[MAX_UPDATE_ARRAY_SIZE];
    private Map<Integer,Byte> eventsHigh;

    // Used by release and updateRegistrations to track whether a file
    // descriptor is registered with epoll.
    private final BitSet registered = new BitSet();


    EPollArrayWrapper() throws IOException {
        // creates the epoll file descriptor
        this.epollData = new EpollData(this);

        // eventHigh needed when using file descriptors > 64k
        if (OPEN_MAX > MAX_UPDATE_ARRAY_SIZE)
            this.eventsHigh = new HashMap<>();

        log("new epoll created");
    }

    void initInterrupt(int fd0, int fd1) {
        this.outgoingInterruptFD = fd1;
        this.incomingInterruptFD = fd0;
        epollCtl(this.epollData.epfd, EPOLL_CTL_ADD, this.incomingInterruptFD, EPOLLIN);
    }

    void putEventOps(int i, int event) {
        this.epollData.putEventOps(i, event);
    }

    void putDescriptor(int i, int fd) {
        this.epollData.putDescriptor(i, fd);
    }

    int getEventOps(int i) {
        return this.epollData.getEventOps(i);
    }

    int getDescriptor(int i) {
        return this.epollData.getDescriptor(i);
    }

    /**
     * Returns {@code true} if updates for the given key (file
     * descriptor) are killed.
     */
    private boolean isEventsHighKilled(Integer key) {
        assert key >= MAX_UPDATE_ARRAY_SIZE;
        Byte value = this.eventsHigh.get(key);
        return (value != null && value == KILLED);
    }

    /**
     * Sets the pending update events for the given file descriptor. This
     * method has no effect if the update events is already set to KILLED,
     * unless {@code force} is {@code true}.
     */
    private void setUpdateEvents(int fd, byte events, boolean force) {
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            if ((this.eventsLow[fd] != KILLED) || force) {
                this.eventsLow[fd] = events;
            } else {
                log("setUpdateEvents(%d, %d, %b) IGNORED because state == KILLED", fd, events, force);
            }
        } else {
            Integer key = Integer.valueOf(fd);
            if (!isEventsHighKilled(key) || force) {
                this.eventsHigh.put(key, Byte.valueOf(events));
            }
        }
    }

    /**
     * Returns the pending update events for the given file descriptor.
     */
    private byte getUpdateEvents(int fd) {
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            return this.eventsLow[fd];
        } else {
            Byte result = this.eventsHigh.get(Integer.valueOf(fd));
            // result should never be null
            return result.byteValue();
        }
    }

    /**
     * Update the events for a given file descriptor
     */
    void setInterest(int fd, int mask) {
        synchronized (this.updateLock) {
            log ("setInterest(%d, %s): fd was %s registered", fd, Events.toString(mask), this.registered.get(fd) ? "already " : "not");

            // record the file descriptor and events
            int oldCapacity = this.updateDescriptors.length;
            if (this.updateCount == oldCapacity) {
                int newCapacity = oldCapacity + INITIAL_PENDING_UPDATE_SIZE;
                int[] newDescriptors = new int[newCapacity];
                System.arraycopy(this.updateDescriptors, 0, newDescriptors, 0, oldCapacity);
                this.updateDescriptors = newDescriptors;
            }
            this.updateDescriptors[this.updateCount++] = fd;

            // events are stored as bytes for efficiency reasons
            byte b = (byte)mask;
            assert (b == mask) && (b != KILLED);
            setUpdateEvents(fd, b, false);
        }
    }

    /**
     * Add a file descriptor
     */
    void add(int fd) {
        // force the initial update events to 0 as it may be KILLED by a
        // previous registration.
        synchronized (this.updateLock) {
            log ("add(%d): fd was %s registered", fd, this.registered.get(fd) ? "already " : "not");
            assert !this.registered.get(fd);
            setUpdateEvents(fd, (byte)0, true);
        }
    }

    /**
     * Remove a file descriptor
     */
    void remove(int fd) {
        synchronized (this.updateLock) {

            // kill pending and future update for this file descriptor
            setUpdateEvents(fd, KILLED, false);

            // remove from epoll
            if (this.registered.get(fd)) {
                log ("remove(%d): fd is registered", fd);

                epollCtl(this.epollData.epfd, EPOLL_CTL_DEL, fd, 0);
                this.registered.clear(fd);
            } else {
                log ("remove(%d) : fd NOT registered ", fd);
            }
        }
    }


    /**
     * Close epoll file descriptor and free poll array
     */
    void closeEPollFD() throws IOException {
        this.epollData.closeEPollFD();
    }

    private void log(String format, Object ... args) {
        System.err.println(toString()+": "+String.format(format, args));
    }


    @Override
    public String toString() {
        return "EPollArrayWrapper [epfd=" + this.epollData.epfd + "]";
    }

    private int bugCount;

    int poll(long timeout) throws IOException {
        updateRegistrations();
        int realUpdates = 0;
        this.updated = epollWait(this.epollData.pollArrayAddress, NUM_EPOLLEVENTS, timeout, this.epollData.epfd);
        for (int i=0; i<this.updated; i++) {
            int fd = getDescriptor(i);
            if (fd == this.incomingInterruptFD) {
                this.interruptedIndex = i;
                this.interrupted = true;
                break;
            } else if (! this.registered.get(fd)) {
                this.bugCount++;
                if (this.bugCount < 5) {
                    log("Received event from %d (state=%d) non registered file descriptor!\n  registered=%s\n  events=%s ",
                            fd,
                            getUpdateEvents(fd),
                            this.registered,
                            Events.toString(getEventOps(i)));
                    try {
                        // try to remove from epoll again ...
                        // Usually it does not work either
                        epollCtl(this.epollData.epfd, EPOLL_CTL_DEL, fd, 0);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (this.bugCount > MAX_BUGS) {
                    // last resort: Prevent CPU burn by throwing runtime exception
                    throw new IllegalStateException(String.format(
                            "Received > %d times event from %d (state=%d) non registered file descriptor!\n  registered=%s\n  events=%s ",
                            MAX_BUGS,
                            fd,
                            getUpdateEvents(fd),
                            this.registered,
                            Events.toString(getEventOps(i))));
                }
            } else {
                realUpdates++;
            }
        }

        if (this.bugCount > 1 && realUpdates==0) {
            // the epoll descriptor is buggy and has no interesting events, remake it.
            rebuildEPollDescriptor();
        }
        return this.updated;
    }

    /**
     * Update the pending registrations.
     */
    private void updateRegistrations() {
        synchronized (this.updateLock) {
            int j = 0;
            while (j < this.updateCount) {
                int fd = this.updateDescriptors[j];
                short events = getUpdateEvents(fd);
                boolean isRegistered = this.registered.get(fd);
                int opcode = 0;

                if (events != KILLED) {
                    if (isRegistered) {
                        opcode = (events != 0) ? EPOLL_CTL_MOD : EPOLL_CTL_DEL;
                    } else {
                        opcode = (events != 0) ? EPOLL_CTL_ADD : 0;
                    }
                    if (opcode != 0) {
                        epollCtl(this.epollData.epfd, opcode, fd, events);
                        if (opcode == EPOLL_CTL_ADD) {
                            this.registered.set(fd);
                        } else if (opcode == EPOLL_CTL_DEL) {
                            this.registered.clear(fd);
                        }
                    }
                }
                j++;
            }
            this.updateCount = 0;
        }
    }

    /**
     * Rebuild the epoll file descriptor from scratch and close the original.
     */
    private void rebuildEPollDescriptor() {
        synchronized (this.updateLock) {
            log("rebuilding epoll descriptor...");
            EpollData newEpoll = new EpollData(this);
            EpollData oldEpoll = this.epollData;

            // same as in initInterrupt(...)
            epollCtl(newEpoll.epfd, EPOLL_CTL_ADD, this.incomingInterruptFD, EPOLLIN);

            // Register all listened file descriptors on the new epoll
            int newi = 0;
            for (int i = this.registered.nextSetBit(0); i >= 0 && i<Integer.MAX_VALUE; i = this.registered.nextSetBit(i+1)) {
                int fd = oldEpoll.getDescriptor(i);
                byte events = getUpdateEvents(fd);
                if (events > 0) {
                    newi++;
                    newEpoll.putDescriptor(newi, fd);
                    epollCtl(newEpoll.epfd, EPOLL_CTL_ADD, fd, events);
                }
            }

            this.epollData = newEpoll;

            try {
                oldEpoll.closeEPollFD();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            this.bugCount = 0;

            log(" rebuilt epoll descriptor: %d -> %d.", oldEpoll.epfd, newEpoll.epfd);
        }
    }


    // interrupt support
    private boolean interrupted = false;

    public void interrupt() {
        interrupt(this.outgoingInterruptFD);
    }

    public int interruptedIndex() {
        return this.interruptedIndex;
    }

    boolean interrupted() {
        return this.interrupted;
    }

    void clearInterrupted() {
        this.interrupted = false;
    }

    static {
        IOUtil.load();
        init();
    }

    private native int epollCreate();
    private native void epollCtl(int epfd, int opcode, int fd, int events);
    private native int epollWait(long pollAddress, int numfds, long timeout,
                                 int epfd) throws IOException;
    private static native int sizeofEPollEvent();
    private static native int offsetofData();
    private static native void interrupt(int fd);
    private static native void init();

    static class Events {
        /**
         * The associated file is available for read(2) operations.
         */
        public static int EPOLLIN = 0x001;
        /**There is an exceptional condition on the file descriptor.  See
              the discussion of POLLPRI in poll(2)*/
        public static int EPOLLPRI = 0x002;
        /**
         * The associated file is available for write(2) operations.
         */
        public static int EPOLLOUT = 0x004;

        public static int EPOLLRDNORM = 0x040;
        public static int EPOLLRDBAND = 0x080;
        public static int EPOLLWRNORM = 0x100;
        public static int EPOLLWRBAND = 0x200;
        public static int EPOLLMSG = 0x400;
        /**
         * Error condition happened on the associated file descriptor.
              This event is also reported for the write end of a pipe when
              the read end has been closed.  epoll_wait(2) will always
              report for this event; it is not necessary to set it in
              events.
         */
        public static int EPOLLERR = 0x008;
        /**
         * Hang up happened on the associated file descriptor.
              epoll_wait(2) will always wait for this event; it is not nec‐
              essary to set it in events.

              Note that when reading from a channel such as a pipe or a
              stream socket, this event merely indicates that the peer
              closed its end of the channel.  Subsequent reads from the
              channel will return 0 (end of file) only after all outstanding
              data in the channel has been consumed.
         */
        public static int EPOLLHUP = 0x010;
        /**
         * Stream socket peer closed connection, or shut down writing
              half of connection.  (This flag is especially useful for writ‐
              ing simple code to detect peer shutdown when using Edge Trig‐
              gered monitoring.)
         */
        public static int EPOLLRDHUP = 0x2000;
        /**
         * Sets the one-shot behavior for the associated file descriptor.
              This means that after an event is pulled out with
              epoll_wait(2) the associated file descriptor is internally
              disabled and no other events will be reported by the epoll
              interface.  The user must call epoll_ctl() with EPOLL_CTL_MOD
              to rearm the file descriptor with a new event mask.

         */
        public static int EPOLLONESHOT = (1 << 30);
        /**
         * Sets the Edge Triggered behavior for the associated file
              descriptor.  The default behavior for epoll is Level Trig‐
              gered.  See epoll(7) for more detailed information about Edge
              and Level Triggered event distribution architectures.
         */
        public static int EPOLLET = (1 << 31);

        public static String toString(int i) {
            StringBuilder s = new StringBuilder();
            if ((i & EPOLLIN) != 0)
              s.append("EPOLLIN ");

          if ((i & EPOLLPRI) != 0)
              s.append("EPOLLPRI ");
          if ((i & EPOLLOUT) != 0)
              s.append("EPOLLOUT ");
          if ((i & EPOLLRDNORM) != 0)
              s.append("EPOLLRDNORM ");
          if ((i & EPOLLRDBAND) != 0)
              s.append("EPOLLRDBAND ");
          if ((i & EPOLLWRNORM) != 0)
              s.append("EPOLLWRNORM ");
          if ((i & EPOLLWRBAND) != 0)
              s.append("EPOLLWRBAND ");
          if ((i & EPOLLMSG) != 0)
              s.append("EPOLLMSG ");
          if ((i & EPOLLERR) != 0)
              s.append("EPOLLERR ");
          if ((i & EPOLLHUP) != 0)
              s.append("EPOLLHUP ");
          if ((i & EPOLLRDHUP) != 0)
              s.append("EPOLLRDHUP ");
          if ((i & EPOLLONESHOT) != 0)
              s.append("EPOLLONESHOT ");
          if ((i & EPOLLET) != 0)
              s.append("EPOLLET ");

          s.append(i);
          return s.toString();
      }
    }

}
