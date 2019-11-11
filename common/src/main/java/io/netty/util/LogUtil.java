package io.netty.util;

public class LogUtil {
    public static void log(String log){
        try {
            StackTraceElement[] temp=Thread.currentThread().getStackTrace();
            StackTraceElement a=(StackTraceElement)temp[2];
            System.out.println(a.getClassName() + ">>>>" + a.getMethodName() + ">>>>" + log);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
