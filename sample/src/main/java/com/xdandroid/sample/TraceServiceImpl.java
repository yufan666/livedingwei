package com.xdandroid.sample;

import android.annotation.SuppressLint;
import android.content.*;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.*;
import android.widget.Toast;

import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.location.Poi;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;
import com.xdandroid.hellodaemon.*;
import com.zhy.http.okhttp.OkHttpUtils;
import com.zhy.http.okhttp.callback.StringCallback;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;

import io.reactivex.*;
import io.reactivex.disposables.*;
import okhttp3.Call;

@SuppressLint("MissingPermission")
public class TraceServiceImpl extends AbsWorkService {

    private LocationClient mLocationClient;
    private LocationClientOption mOption;
    //是否 任务完成, 不再需要服务运行?
    public static boolean sShouldStopService;
    public static Disposable sDisposable;

    public static void stopService() {
        //我们现在不再需要服务运行了, 将标志位置为 true
        sShouldStopService = true;
        //取消对任务的订阅
        if (sDisposable != null) sDisposable.dispose();
        //取消 Job / Alarm / Subscription
        cancelJobAlarmSub();

    }

    @Override
    public void onDestroy() {
        mLocationClient.unRegisterLocationListener(mListener); // 注销掉监听
        if(mLocationClient != null && mLocationClient.isStarted()){
            mLocationClient.stop(); // 停止定位服务
        }
        super.onDestroy();
    }

    /**
     * 是否 任务完成, 不再需要服务运行?
     * @return 应当停止服务, true; 应当启动服务, false; 无法判断, 什么也不做, null.
     */
    @Override
    public Boolean shouldStopService(Intent intent, int flags, int startId) {
        return sShouldStopService;
    }

    @Override
    public void startWork(Intent intent, int flags, int startId) {
        System.out.println("检查磁盘中是否有上次销毁时保存的数据");
        mLocationClient = new LocationClient(getApplication());
        mLocationClient.setLocOption(getDefaultLocationClientOption());
        mLocationClient.registerLocationListener(mListener);
        if(mLocationClient != null && !mLocationClient.isStarted()){
            mLocationClient.start();
        }

        GPSstart();
        sDisposable = Observable
                .interval(10, TimeUnit.SECONDS)
                //取消任务时取消定时唤醒
                .doOnDispose(() -> {
                    System.out.println("保存数据到磁盘。");
                    cancelJobAlarmSub();
                })
                .subscribe(count -> {
                    System.out.println("每 10 秒采集一次数据... count = " + count);



                    if (count > 0 && count % 18 == 0) System.out.println("保存数据到磁盘。 saveCount = " + (count / 18 - 1));
                });
    }

    @Override
    public void stopWork(Intent intent, int flags, int startId) {
        stopService();
    }

    /**
     * 任务是否正在运行?
     * @return 任务正在运行, true; 任务当前不在运行, false; 无法判断, 什么也不做, null.
     */
    @Override
    public Boolean isWorkRunning(Intent intent, int flags, int startId) {
        //若还没有取消订阅, 就说明任务仍在运行.
        return sDisposable != null && !sDisposable.isDisposed();
    }

    @Override
    public IBinder onBind(Intent intent, Void v) {
        return null;
    }

    @Override
    public void onServiceKilled(Intent rootIntent) {
        System.out.println("保存数据到磁盘。");
    }
    public LocationClientOption getDefaultLocationClientOption(){
        if(mOption == null){
            mOption = new LocationClientOption();
            /**
             * 默认高精度，设置定位模式
             * LocationMode.Hight_Accuracy 高精度定位模式：这种定位模式下，会同时使用网络定位和GPS定位，优先返回最高精度的定位结果
             * LocationMode.Battery_Saving 低功耗定位模式：这种定位模式下，不会使用GPS，只会使用网络定位（Wi-Fi和基站定位）
             * LocationMode.Device_Sensors 仅用设备定位模式：这种定位模式下，不需要连接网络，只使用GPS进行定位，这种模式下不支持室内环境的定位
             */
            mOption.setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);

            /**
             * 默认gcj02，设置返回的定位结果坐标系，如果配合百度地图使用，建议设置为bd09ll;
             * 目前国内主要有以下三种坐标系：
             1. wgs84：目前广泛使用的GPS全球卫星定位系统使用的标准坐标系；
             2. gcj02：经过国测局加密的坐标；
             3. bd09：为百度坐标系，其中bd09ll表示百度经纬度坐标，bd09mc表示百度墨卡托米制坐标；
             * 海外地区定位结果默认、且只能是wgs84类型坐标
             */
            mOption.setCoorType("bd09ll");

            /**
             * 默认0，即仅定位一次；设置间隔需大于等于1000ms，表示周期性定位
             * 如果不在AndroidManifest.xml声明百度指定的Service，周期性请求无法正常工作
             * 这里需要注意的是：如果是室外gps定位，不用访问服务器，设置的间隔是1秒，那么就是1秒返回一次位置
             如果是WiFi基站定位，需要访问服务器，这个时候每次网络请求时间差异很大，设置的间隔是3秒，只能大概保证3秒左右会返回就一次位置，有时某次定位可能会5秒返回
             */
            mOption.setScanSpan(10000);

            /**
             * 默认false，设置是否需要地址信息
             * 返回省市区等地址信息，这个api用处很大，很多新闻类api会根据定位返回的市区信息推送用户所在市的新闻
             */
            mOption.setIsNeedAddress(true);

            /**
             * 默认是true，设置是否使用gps定位
             * 如果设置为false，即使mOption.setLocationMode(LocationMode.Hight_Accuracy)也不会gps定位
             */
            mOption.setOpenGps(true);

            /**
             * 默认false，设置是否需要位置语义化结果
             * 可以在BDLocation.getLocationDescribe里得到，结果类似于“在北京天安门附近”
             */
            mOption.setIsNeedLocationDescribe(true);//

            /**
             * 默认false,设置是否需要设备方向传感器的方向结果
             * 一般在室外gps定位，时返回的位置信息是带有方向的，但是有时候gps返回的位置也不带方向，这个时候可以获取设备方向传感器的方向
             * wifi基站定位的位置信息是不带方向的，如果需要可以获取设备方向传感器的方向
             */
            mOption.setNeedDeviceDirect(false);

            /**
             * 默认false，设置是否当gps有效时按照1S1次频率输出GPS结果
             * 室外gps有效时，周期性1秒返回一次位置信息，其实就是设置了
             locationManager.requestLocationUpdates中的minTime参数为1000ms，1秒回调一个gps位置
             */
            mOption.setLocationNotify(false);

            /**
             * 默认true，定位SDK内部是一个SERVICE，并放到了独立进程，设置是否在stop的时候杀死这个进程，默认不杀死
             * 如果你已经拿到了你要的位置信息，不需要再定位了，不杀死留着干嘛
             */
            mOption.setIgnoreKillProcess(true);

            /**
             * 默认false，设置是否需要POI结果，可以在BDLocation.getPoiList里得到
             * POI就是获取到的位置附近的一些商场、饭店、银行等信息
             */
            mOption.setIsNeedLocationPoiList(true);

            /**
             * 默认false，设置是否收集CRASH信息，默认收集
             */
            mOption.SetIgnoreCacheException(false);

        }
        return mOption;
    }
    /*****
     *
     * 定位结果回调，重写onReceiveLocation方法，可以直接拷贝如下代码到自己工程中修改
     *
     */
    private BDLocationListener mListener = new BDLocationListener() {

        @Override
        public void onReceiveLocation(BDLocation location) {

            StringBuffer sb = new StringBuffer(256);
            sb.append("Thread : " + Thread.currentThread().getName());
            sb.append("\nphone : " + System.currentTimeMillis());
            sb.append("\ntime : ");
            sb.append(location.getTime());
            sb.append("\nlocType : ");// 定位类型
            sb.append(location.getLocType());
            sb.append("\nlatitude : ");// 纬度
            sb.append(location.getLatitude());
            sb.append("\nlontitude : ");// 经度
            sb.append(location.getLongitude());
            sb.append("\nradius : ");// 半径
            sb.append(location.getRadius());
            sb.append("\nCountryCode : ");// 国家码
            sb.append(location.getCountryCode());
            sb.append("\nCountry : ");// 国家名称
            sb.append(location.getCountry());
            sb.append("\ncitycode : ");// 城市编码
            sb.append(location.getCityCode());
            sb.append("\ncity : ");// 城市
            sb.append(location.getCity());
            sb.append("\nDistrict : ");// 区
            sb.append(location.getDistrict());
            sb.append("\nStreet : ");// 街道
            sb.append(location.getStreet());
            sb.append("\naddr : ");// 地址信息
            sb.append(location.getAddrStr());
            sb.append("\nDirection(not all devices have value): ");
            sb.append(location.getDirection());// 方向
            sb.append("\nlocationdescribe: ");
            sb.append(location.getLocationDescribe());// 位置语义化信息
            sb.append("\nPoi: ");// POI信息
            if (location.getPoiList() != null && !location.getPoiList().isEmpty()) {
                for (int i = 0; i < location.getPoiList().size(); i++) {
                    Poi poi = (Poi) location.getPoiList().get(i);
                    sb.append(poi.getName() + ";");
                }
            }
            if (location.getLocType() == BDLocation.TypeGpsLocation) {// GPS定位结果
                sb.append("\nspeed : ");
                sb.append(location.getSpeed());// 速度 单位：km/h
                sb.append("\nsatellite : ");
                sb.append(location.getSatelliteNumber());// 卫星数目
                sb.append("\nheight : ");
                sb.append(location.getAltitude());// 海拔高度 单位：米
                sb.append("\ndescribe : ");
                sb.append("gps定位成功");
            } else if (location.getLocType() == BDLocation.TypeNetWorkLocation) {// 网络定位结果
                // 运营商信息
                if (location.hasAltitude()) {// *****如果有海拔高度*****
                    sb.append("\nheight : ");
                    sb.append(location.getAltitude());// 单位：米
                }
                sb.append("\noperationers : ");// 运营商信息
                sb.append(location.getOperators());
                sb.append("方向1：" + location.getDerect());
                sb.append("方向2：" + location.getDirection());
                sb.append("\ndescribe : ");
                sb.append("网络定位成功");
            } else if (location.getLocType() == BDLocation.TypeOffLineLocation) {// 离线定位结果
                sb.append("\ndescribe : ");
                sb.append("离线定位成功，离线定位结果也是有效的");
            } else if (location.getLocType() == BDLocation.TypeServerError) {
                sb.append("\ndescribe : ");
                sb.append("服务端网络定位失败，可以反馈IMEI号和大体定位时间到loc-bugs@baidu.com，会有人追查原因");
            } else if (location.getLocType() == BDLocation.TypeNetWorkException) {
                sb.append("\ndescribe : ");
                sb.append("网络不同导致定位失败，请检查网络是否通畅");
            } else if (location.getLocType() == BDLocation.TypeCriteriaException) {
                sb.append("\ndescribe : ");
                sb.append("无法获取有效定位依据导致定位失败，一般是由于手机的原因，处于飞行模式下一般会造成这种结果，可以试着重启手机");
            }
            OkHttpUtils
                    .post()
                    .url("http://ghosthgy.top/baidumap/updata.php")
                    .addParams("time", location.getTime())
                    .addParams("latitude", location.getLatitude()+"")
                    .addParams("lontitude", location.getLongitude()+"")
                    .addParams("addr", location.getAddrStr())//_poi
                    .addParams("total", sb.toString())
                    .build()
                    .execute(new StringCallback() {
                        @Override
                        public void onError(Call call, Exception e, int id) {

                            System.out.println(e.toString());
                        }

                        @Override
                        public void onResponse(String response, int id) {
                            System.out.println("---------"+response);
                        }
                    });
//			String url = "http://ghosthgy.top/baidumap/updata.php";
//			StringRequest request = new StringRequest(Request.Method.POST, url,
//					new Response.Listener<String>() {
//						@Override
//						public void onResponse(String s) {//s为请求返回的字符串数据
//							System.out.println("---------"+s);
//						}
//					},
//					new Response.ErrorListener() {
//						@Override
//						public void onErrorResponse(VolleyError volleyError) {
//							System.out.println("---------"+volleyError);
//						}
//					}){
//				@Override
//				protected Map<String, String> getParams() throws AuthFailureError {
//					Map<String,String> map = new HashMap<>();
//					//将请求参数名与参数值放入map中
//					map.put("time",location.getTime());
//					map.put("latitude", location.getLatitude()+"");
//					map.put("lontitude", location.getLongitude()+"");
//					map.put("addr", location.getAddrStr());
//					map.put("total", sb.toString());
//
//					return map;
//				}
//			}
//					;
//			//设置请求的Tag标签，可以在全局请求队列中通过Tag标签进行请求的查找
//			request.setTag("testPost");
//			//将请求加入全局队列中
//			DemoApplication.getHttpQueues().add(request);
        }

    };
    public void GPSstart(){
        //获取LocationManager
        LocationManager lManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        /**
         * 参1:选择定位的方式
         * 参2:定位的间隔时间
         * 参3:当位置改变多少时进行重新定位
         * 参4:位置的回调监听
         */
        lManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 0, new LocationListener() {
            //当位置改变的时候调用
            @Override
            public void onLocationChanged(Location location) {

                //经度
                double longitude = location.getLongitude();
                //纬度
                double latitude = location.getLatitude();

                //海拔
                double altitude = location.getAltitude();
                LatLng desLatLng=gpstobaidu(new LatLng(latitude,longitude));
                System.out.println("------经度:==>"+desLatLng.longitude+" 纬度==>"+desLatLng.latitude+"\n");
                OkHttpUtils
                        .post()
                        .url("http://ghosthgy.top/baidumap/sample.php")
                        .addParams("time", TimeUtil.getTime())
                        .addParams("total", "经度:==>"+longitude+" \n 纬度==>"+latitude+"\n"+"海拔==>"+altitude)
                        .build()
                        .execute(new StringCallback() {
                            @Override
                            public void onError(Call call, Exception e, int id) {

                                System.out.println(e.toString());
                            }

                            @Override
                            public void onResponse(String response, int id) {
                                System.out.println("---------"+response);
                            }
                        });
                OkHttpUtils
                        .post()
                        .url("http://ghosthgy.top/baidumap/gpsmap.php")
                        .addParams("time", TimeUtil.getTime())
                        .addParams("latitude", desLatLng.latitude+"")
                        .addParams("lontitude", desLatLng.longitude+"")
                        .addParams("total", "经度:==>"+desLatLng.longitude+" 纬度==>"+desLatLng.latitude)
                        .build()
                        .execute(new StringCallback() {
                            @Override
                            public void onError(Call call, Exception e, int id) {

                                System.out.println(e.toString());
                            }

                            @Override
                            public void onResponse(String response, int id) {
                                System.out.println("---------GPS"+response);
                            }
                        });
                System.out.println("经度:==>"+longitude+" \n 纬度==>"+latitude+"\n"+"海拔==>"+altitude);
            }

            //当GPS状态发生改变的时候调用
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {


                switch (status){

                    case LocationProvider.AVAILABLE:

//                        Toast.makeText(TraceServiceImpl.this,"当前GPS为可用状态!",Toast.LENGTH_SHORT).show();

                        break;

                    case LocationProvider.OUT_OF_SERVICE:

//                        Toast.makeText(TraceServiceImpl.this,"当前GPS不在服务内",Toast.LENGTH_SHORT).show();

                        break;

                    case LocationProvider.TEMPORARILY_UNAVAILABLE:

//                        Toast.makeText(TraceServiceImpl.this,"当前GPS为暂停服务状态",Toast.LENGTH_SHORT).show();
                        break;


                }

            }

            //GPS开启的时候调用
            @Override
            public void onProviderEnabled(String provider) {

                Toast.makeText(TraceServiceImpl.this,"GPS开启了",Toast.LENGTH_SHORT).show();

            }

            //GPS关闭的时候调用
            @Override
            public void onProviderDisabled(String provider) {

                Toast.makeText(TraceServiceImpl.this,"GPS关闭了",Toast.LENGTH_SHORT).show();

            }
        });


    }
    private LatLng  coordinateConvert(LatLng sourceLatLng){

        CoordinateConverter converter  = new CoordinateConverter();
        converter.from(CoordinateConverter.CoordType.COMMON);;
        converter.coord(sourceLatLng);
        return converter.convert();
    }
    private LatLng  gpstobaidu(LatLng sourceLatLng){

        CoordinateConverter converter  = new CoordinateConverter();
        converter.from(CoordinateConverter.CoordType.GPS);;
        converter.coord(sourceLatLng);
        return converter.convert();
    }
}
