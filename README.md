# EPollArrayWrapper-bug-demonstrator
Demonstrate sun.nio.ch.EPollArrayWrapper bug where EPollArrayWrapper.epollWait(...) may return events for file descriptors that were previously removed.
