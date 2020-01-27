# EPollArrayWrapper.epollWait(...) issue

These maven projects demonstrate that sun.nio.ch.EPollArrayWrapper bug where EPollArrayWrapper.epollWait(...)
 may return events for file descriptors that were previously removed.

The consequences are that Jetty spins into infinite loops and consumes CPU for nothing.

This repository contains 2 projects:
- one to reproduce the bug
- the other is a java agent that patches the EPollArrayWrapper JDK class to fix the issue

## How to execute:

1. Extract the jar file in a directory
1. Go to the directory
1. Execute `maven package`
1. Execute `java -jar './target/org.modelio.jre.epollarray.test-0.0.1-jar-with-dependencies.jar`
