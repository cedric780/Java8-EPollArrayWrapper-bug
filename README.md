# EPollArrayWrapper.epollWait(...) issue

These maven projects demonstrate that `sun.nio.ch.EPollArrayWrapper` bug where `EPollArrayWrapper.epollWait(...)`
 may return events for file descriptors that were previously removed.

The consequences are:
1. Selector.select() returns immediately with 0 event
1. Jetty then spins into infinite loops and consumes one CPU core at 100% for nothing.

I have reproduced this issue with many JDK 8, and seems to be fixed on JDK 11.

It is registered in Java bug system at [[JDK-8238279] EPollArrayWrapper.epollWait() may return events for removed file descriptors - Java Bug System](https://bugs.openjdk.java.net/browse/JDK-8238279).

This repository contains 2 projects:
- **demonstrator** : reproduces the bug
- **jre-patch** : this is a java agent that patches the EPollArrayWrapper JDK class to fix the issue

## How to execute the demonstator

1. Clone or download the repository
1. Go to the `demonstrator` directory
1. Execute `maven package`
1. Execute `java -jar './target/org.modelio.jre.epollarray.test-0.0.1-jar-with-dependencies.jar`


## Possibly related issues

This issue may be the cause of the following ones:
- https://github.com/netty/netty/issues/8306
- https://bz.apache.org/bugzilla/show_bug.cgi?id=63802
- https://github.com/vlingo/vlingo-wire/issues/28


## Possible cause

This web page explains a possible cause the JDK issue: https://idea.popcount.org/2017-03-20-epoll-is-fundamentally-broken-22/
