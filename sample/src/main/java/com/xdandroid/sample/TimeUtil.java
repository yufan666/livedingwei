package com.xdandroid.sample;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by Administrator on 2019/4/23.
 */

public class TimeUtil {
    public static String getTime(){
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");// HH:mm:ss//获取当前时间
        Date date = new Date(System.currentTimeMillis());
        return simpleDateFormat.format(date);
    }
}
