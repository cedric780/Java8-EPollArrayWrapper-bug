package org.modelio.jre.epollarray.test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

@SuppressWarnings ("javadoc")
public class TestEpollArrayWrapper
{

    public static class MainServlet  extends HttpServlet {
        static final OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/html;charset=utf-8");
            double sysLoad = osBean.getSystemLoadAverage();

            response.getWriter().printf("<h1>%s</h1>"+
                    "<h2>System load : %.2f%% </h2>"+
                    "<img src='/res/icon.jpg'></img>",
                    request.getParameter("name"),
                    sysLoad*100
                    );
        }
    }
    /**
     * Connection idle time out
     */
    static final int IDLE_TIMEOUT = 5_000;

    /**
     * Time to wait before second HTTP request, must be > than IDLE_TIMEOUT .
     */
    static final int LONG_PAUSE = IDLE_TIMEOUT + 1_000;

    /**
     * Time to wait before disposing the browser widget.
     */
    static final int DISPOSE_PAUSE = IDLE_TIMEOUT / 2 ;

    private static class SwtRunner {

        final Display display = new Display();
        final Shell shell ;
        private Browser browser ;
        private int count;

        public SwtRunner() {
            this.shell = new Shell(this.display);
            this.shell.setText("Test EPollArrayWrapper bug");
            this.shell.setLayout(new FillLayout());
            this.browser = new Browser(this.shell, SWT.BORDER);

            Browser b2 = new Browser(this.shell, SWT.BORDER);
            b2.setUrl("localhost:8080/main/aa?name=This browser will stay here.");
        }

        private void test() {
            String burnMessage = "The console should now be filled with EPollSelectorImpl messages and at least one CPU core burning";

            this.browser.setUrl("localhost:8080/main/?name=Initial request, this browser will dispose in "+DISPOSE_PAUSE/1000+" seconds.");
            this.display.timerExec(DISPOSE_PAUSE, () -> {
                this.browser.dispose();
            });

            this.display.timerExec(LONG_PAUSE,() -> {
                this.browser = new Browser(this.shell, SWT.BORDER);
                this.shell.layout();
                this.browser.setVisible(true);
                this.browser.setUrl("localhost:8080/main/aa?name="+burnMessage);
            });

            this.display.timerExec(LONG_PAUSE + DISPOSE_PAUSE,() -> {
                this.browser.setUrl("localhost:8080/main/aa?name="+burnMessage+" "+this.count++);
            });
            this.display.timerExec(LONG_PAUSE + DISPOSE_PAUSE*2,() -> {
                this.browser.setUrl("localhost:8080/main/aa?name="+burnMessage+" "+this.count++);
            });
        }

        public void run() {

            this.shell.open();

            test();

            while (! this.shell.isDisposed()) {
                if (! this.display.readAndDispatch())
                    this.display.sleep();
            }
        }

    }

    /**
     * Figure out what path to serve content from
     */
    private static URI getWebRootUri() throws URISyntaxException {
        // Figure out what path to serve content from
        ClassLoader cl = TestEpollArrayWrapper.class.getClassLoader();

        // We look for a file, as ClassLoader.getResource() is not
        // designed to look for directories (we resolve the directory later)
        URL f = cl.getResource("static-root/icon.jpg");
        if (f == null)
        {
            throw new RuntimeException("Unable to find resource directory");
        }

        // Resolve file to directory
       URI webRootUri = URI.create(f.toURI().normalize().toASCIIString().replace("/icon.jpg", ""));

        System.err.println("WebRoot is '" + webRootUri+"'");

        return webRootUri;
    }

    public static void main(String[] args) throws Exception
    {
        System.setProperty("org.eclipse.jetty.LEVEL", "DEBUG");

        Server server = new Server(new QueuedThreadPool(4, 2, 120_000));

        @SuppressWarnings ("resource")
        ServerConnector connector = new ServerConnector(server);
        connector.setIdleTimeout(IDLE_TIMEOUT);
        connector.setPort(8080);
        connector.setHost("0.0.0.0");

        server.setConnectors(new Connector[] {connector});

        configureServletHandlers(server);

        try {
            new Thread(()  -> {
                try {
                    server.start();

                    // Dump the server state
                    System.out.println(server.dump());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, "Jetty Start thread").start();

            new SwtRunner().run();

        } finally {
            server.stop();
        }
    }

    private static void configureServletHandlers(Server server) throws MalformedURLException, URISyntaxException {

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setBaseResource(Resource.newResource(getWebRootUri()));
        server.setHandler(context);

        ServletHolder holderPwd = new ServletHolder("main servlet",MainServlet.class);
        holderPwd.setInitParameter("dirAllowed","true");
        context.addServlet(holderPwd,"/main/*");

        ServletHolder holder2 = new ServletHolder("res servlet",DefaultServlet.class);
        holder2.setInitParameter("dirAllowed","true");
        holder2.setInitParameter("pathInfoOnly","true");
        context.addServlet(holder2,"/res/*");

        SessionHandler handler = new SessionHandler();
        handler.setMaxInactiveInterval(-1);
        context.setSessionHandler(handler);

    }
}