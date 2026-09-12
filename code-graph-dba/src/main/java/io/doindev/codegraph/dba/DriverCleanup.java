package io.doindev.codegraph.dba;

import java.sql.*;

/** Loaded inside each isolated driver loader so DriverManager's caller checks permit cleanup. */
public final class DriverCleanup {
    public static void deregister() throws SQLException {
        var drivers=DriverManager.getDrivers();
        while(drivers.hasMoreElements()){Driver d=drivers.nextElement();if(d.getClass().getClassLoader()==DriverCleanup.class.getClassLoader())DriverManager.deregisterDriver(d);}
    }
}
