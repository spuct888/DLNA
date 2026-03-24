package com.dlna.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DlnaService extends Service {
    private static final String TAG = "DlnaService";
    private static final String CHANNEL_ID = "dlna_service_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int HTTP_PORT = 8081;

    private WifiManager.MulticastLock multicastLock;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;
    private ServerSocket httpServerSocket;

    private static DlnaService instance;
    private String currentUri = "";
    private String currentTitle = "Unknown";
    private int currentState = 0;
    private long currentPosition = 0;
    private long duration = 0;
    private int volume = 50;
    private boolean isMuted = false;
    private boolean isServiceStarted = false;
    private String localIpAddress = "127.0.0.1";
    private byte[] messageBuffer = new byte[4096];
    private int messageLength = 0;

    public interface OnDlnaEventListener {
        void onPlay(String uri, String title);
        void onDlnaPause();
        void onDlnaStop();
        void onSeek(long position);
        void onSetVolume(int volume);
    }

    private OnDlnaEventListener eventListener;

    public void setOnDlnaEventListener(OnDlnaEventListener listener) {
        this.eventListener = listener;
    }

    public static DlnaService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        executorService = Executors.newCachedThreadPool();
        Log.d(TAG, "DLNA服务已创建，开始初始化...");
        Log.d(TAG, "ExecutorService创建成功: " + (executorService != null));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand被调用，开始启动前台服务...");
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "前台服务已启动");
        
        if (!isServiceStarted) {
            Log.d(TAG, "服务未启动，开始启动服务...");
            isServiceStarted = true;
            try {
                Log.d(TAG, "开始获取WifiManager...");
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    Log.d(TAG, "获取WifiManager成功");
                    multicastLock = wifi.createMulticastLock("DLNA");
                    if (multicastLock != null) {
                        Log.d(TAG, "创建组播锁成功");
                        multicastLock.setReferenceCounted(true);
                        try {
                            multicastLock.acquire();
                            Log.d(TAG, "已获取组播锁");
                        } catch (Exception e) {
                            Log.e(TAG, "获取组播锁失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        Log.e(TAG, "创建组播锁失败");
                    }
                } else {
                    Log.e(TAG, "获取WifiManager失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "组播锁操作失败: " + e.getMessage());
                e.printStackTrace();
            }

            Log.d(TAG, "准备延迟启动DLNA服务...");
            startDlnaServiceWithDelay();
        } else {
            Log.d(TAG, "服务已经启动，跳过启动流程");
        }

        return START_STICKY;
    }

    private void startDlnaServiceWithDelay() {
        mainHandler.postDelayed(() -> {
            startDlnaService();
        }, 3000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                Log.d(TAG, "已关闭线程池");
            }
            if (httpServerSocket != null && !httpServerSocket.isClosed()) {
                httpServerSocket.close();
                Log.d(TAG, "已关闭HTTP服务器");
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                Log.d(TAG, "已释放组播锁");
            }
        } catch (Exception e) {
            Log.e(TAG, "释放资源失败: " + e.getMessage());
            e.printStackTrace();
        }
        instance = null;
        Log.d(TAG, "DLNA服务已销毁");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startDlnaService() {
        Log.d(TAG, "开始启动DLNA服务...");
        
        localIpAddress = getLocalIpAddress();
        Log.d(TAG, "本地IP地址: " + localIpAddress);
        
        executorService.execute(() -> {
            try {
                Log.d(TAG, "正在启动DLNA服务...");
                
                Thread.sleep(1000);
                
                Log.d(TAG, "准备启动HTTP服务器...");
                startHttpServer();
                Log.d(TAG, "HTTP服务器启动完成");
                
                Log.d(TAG, "准备启动组播监听器...");
                startMulticastListener();
                Log.d(TAG, "组播监听器启动完成");
                
                Log.d(TAG, "准备启动SSDP广播...");
                startAnnouncement();
                Log.d(TAG, "SSDP广播启动完成");
                
                mainHandler.post(() -> {
                    updateNotification("DLNA服务已启动，等待投屏...");
                });
                
                Log.d(TAG, "DLNA服务启动成功！本地IP: " + localIpAddress + ", HTTP端口: " + HTTP_PORT);
                
            } catch (Exception e) {
                Log.e(TAG, "启动DLNA服务失败: " + e.getMessage());
                e.printStackTrace();
                
                mainHandler.post(() -> {
                    updateNotification("DLNA服务启动失败");
                });
            }
        });
    }

    private void startHttpServer() {
        executorService.execute(() -> {
            try {
                httpServerSocket = new ServerSocket();
                httpServerSocket.setReuseAddress(true);
                httpServerSocket.bind(new InetSocketAddress(InetAddress.getByName(localIpAddress), HTTP_PORT));
                httpServerSocket.setSoTimeout(30000);
                Log.d(TAG, "HTTP服务器已启动，端口: " + HTTP_PORT + ", 绑定地址: " + localIpAddress);
                
                while (isServiceStarted) {
                    try {
                        Socket clientSocket = httpServerSocket.accept();
                        handleHttpRequest(clientSocket);
                    } catch (SocketTimeoutException e) {
                        if (isServiceStarted) {
                            Log.d(TAG, "等待客户端连接超时，继续监听...");
                        }
                    } catch (IOException e) {
                        if (isServiceStarted) {
                            Log.e(TAG, "接受客户端连接失败: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "启动HTTP服务器失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleHttpRequest(Socket clientSocket) {
        try {
            Log.d(TAG, "收到HTTP请求连接，客户端地址: " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String requestLine = reader.readLine();
            Log.d(TAG, "HTTP请求行: " + requestLine);
            
            // 读取所有请求头
            String headerLine;
            StringBuilder headers = new StringBuilder();
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                headers.append(headerLine).append("\n");
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(16).trim());
                }
            }
            Log.d(TAG, "HTTP请求头:\n" + headers.toString());
            
            // 读取请求体（如果有）
            String requestBody = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                reader.read(bodyChars, 0, contentLength);
                requestBody = new String(bodyChars);
                Log.d(TAG, "HTTP请求体长度: " + requestBody.length() + "字节");
            }
            
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";
                Log.d(TAG, "HTTP请求方法: " + method + ", 路径: " + path);
                
                String response;
                if (path.equals("/device.xml") || path.equals("/")) {
                    Log.d(TAG, "请求设备描述XML");
                    response = getDeviceDescriptionXml();
                } else if (path.contains("AVTransport") && path.contains("scpd")) {
                    Log.d(TAG, "请求AVTransport服务描述XML");
                    response = getAVTransportScpdXml();
                } else if (path.contains("ConnectionManager") && path.contains("scpd")) {
                    Log.d(TAG, "请求ConnectionManager服务描述XML");
                    response = getConnectionManagerScpdXml();
                } else if (path.contains("RenderingControl") && path.contains("scpd")) {
                    Log.d(TAG, "请求RenderingControl服务描述XML");
                    response = getRenderingControlScpdXml();
                } else if (path.contains("AVTransport") && path.contains("control")) {
                    Log.d(TAG, "请求AVTransport控制操作");
                    // 解析SOAP请求
                    String soapAction = "";
                    String headersStr = headers.toString();
                    String[] headerLines = headersStr.split("\n");
                    for (String line : headerLines) {
                        if (line.toLowerCase().startsWith("soapaction:")) {
                            soapAction = line.substring(12).trim().toLowerCase();
                            // 移除引号
                            if (soapAction.startsWith("\"")) {
                                soapAction = soapAction.substring(1, soapAction.length() - 1);
                            }
                            break;
                        }
                    }
                    Log.d(TAG, "SOAPAction: " + soapAction);
                    if (soapAction.contains("setavtransporturi")) {
                        Log.d(TAG, "处理SetAVTransportURI操作");
                        Log.d(TAG, "SOAP请求体: " + requestBody);
                        // 从请求体中提取视频URL
                        if (requestBody.contains("CurrentURI")) {
                            int start = requestBody.indexOf("<CurrentURI>") + 11;
                            int end = requestBody.indexOf("</CurrentURI>");
                            if (start > 0 && end > start) {
                                String url = requestBody.substring(start, end).trim();
                                // 移除可能的多余字符
                                if (url.startsWith(">")) {
                                    url = url.substring(1);
                                }
                                Log.d(TAG, "视频URL: " + url);
                                currentUri = url;
                                currentState = 1; // 设置为播放状态
                            } else {
                                Log.d(TAG, "无法提取视频URL，start: " + start + ", end: " + end);
                            }
                        } else {
                            Log.d(TAG, "请求体中不包含CurrentURI");
                        }
                        response = getSetAVTransportURIResponseXml();
                    } else if (soapAction.contains("play")) {
                        Log.d(TAG, "处理Play操作");
                        // 触发播放事件
                        if (eventListener != null && !currentUri.isEmpty()) {
                            eventListener.onPlay(currentUri, "视频");
                        }
                        response = getPlayResponseXml();
                    } else if (soapAction.contains("stop")) {
                        Log.d(TAG, "处理Stop操作");
                        // 触发停止事件
                        if (eventListener != null) {
                            eventListener.onDlnaStop();
                        }
                        response = getStopResponseXml();
                    } else if (soapAction.contains("pause")) {
                        Log.d(TAG, "处理Pause操作");
                        // 触发暂停事件
                        if (eventListener != null) {
                            eventListener.onDlnaPause();
                        }
                        response = getPauseResponseXml();
                    } else if (soapAction.contains("gettransportinfo")) {
                        Log.d(TAG, "处理GetTransportInfo操作");
                        response = getTransportInfoResponseXml();
                    } else if (soapAction.contains("getpositioninfo")) {
                        Log.d(TAG, "处理GetPositionInfo操作");
                        response = getPositionInfoResponseXml();
                    } else {
                        Log.d(TAG, "未知的SOAP操作: " + soapAction);
                        response = getAVTransportControlXml();
                    }
                } else if (path.contains("ConnectionManager") && path.contains("control")) {
                    Log.d(TAG, "请求ConnectionManager控制操作");
                    response = getConnectionManagerControlXml();
                } else if (path.contains("RenderingControl") && path.contains("control")) {
                    Log.d(TAG, "请求RenderingControl控制操作");
                    response = getRenderingControlControlXml();
                } else {
                    Log.d(TAG, "请求路径不存在: " + path);
                    response = "HTTP/1.1 404 Not Found\r\n\r\n";
                }
                
                byte[] responseBytes = response.getBytes("UTF-8");
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                        "Content-Length: " + responseBytes.length + "\r\n" +
                        "Connection: close\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Server: Android/7.0, UPnP/1.0, DLNA/1.50\r\n" +
                        "\r\n";
                
                Log.d(TAG, "HTTP响应头:\n" + header);
                Log.d(TAG, "HTTP响应体长度: " + responseBytes.length + "字节");
                
                clientSocket.getOutputStream().write(header.getBytes("UTF-8"));
                clientSocket.getOutputStream().write(responseBytes);
                clientSocket.getOutputStream().flush();
                Log.d(TAG, "HTTP响应发送成功");
            }
            
            clientSocket.close();
            Log.d(TAG, "HTTP连接已关闭");
        } catch (Exception e) {
            Log.e(TAG, "处理HTTP请求失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getDeviceDescriptionXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<device>" +
                "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
                "<friendlyName>DLNA接收器</friendlyName>" +
                "<manufacturer>DLNA</manufacturer>" +
                "<modelDescription>DLNA Media Renderer</modelDescription>" +
                "<modelName>DLNA Receiver</modelName>" +
                "<modelNumber>1.0</modelNumber>" +
                "<serialNumber>123456</serialNumber>" +
                "<UDN>uuid:test-device</UDN>" +
                "<serviceList>" +
                "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
                "<SCPDURL>/AVTransport/scpd.xml</SCPDURL>" +
                "<controlURL>/AVTransport/control.xml</controlURL>" +
                "<eventSubURL>/AVTransport/event.xml</eventSubURL>" +
                "</service>" +
                "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>" +
                "<SCPDURL>/ConnectionManager/scpd.xml</SCPDURL>" +
                "<controlURL>/ConnectionManager/control.xml</controlURL>" +
                "<eventSubURL>/ConnectionManager/event.xml</eventSubURL>" +
                "</service>" +
                "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>" +
                "<SCPDURL>/RenderingControl/scpd.xml</SCPDURL>" +
                "<controlURL>/RenderingControl/control.xml</controlURL>" +
                "<eventSubURL>/RenderingControl/event.xml</eventSubURL>" +
                "</service>" +
                "</serviceList>" +
                "</device></root>";
    }

    private String getAVTransportScpdXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<actionList>" +
                "<action><name>SetAVTransportURI</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>" +
                "<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>Play</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>Pause</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>Stop</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>Seek</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>" +
                "<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>GetTransportInfo</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>GetPositionInfo</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>Track</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Track</relatedStateVariable></argument>" +
                "<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Duration</relatedStateVariable></argument>" +
                "<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RelTime</relatedStateVariable></argument>" +
                "<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AbsTime</relatedStateVariable></argument>" +
                "<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RelCount</relatedStateVariable></argument>" +
                "<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AbsCount</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "</actionList>" +
                "<serviceStateTable>" +
                "<stateVariable><name>TransportState</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>AVTransportURI</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_Track</name><dataType>ui4</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_Duration</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_RelTime</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_AbsTime</name><dataType>string</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_RelCount</name><dataType>ui4</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_AbsCount</name><dataType>ui4</dataType></stateVariable>" +
                "</serviceStateTable>" +
                "</scpd>";
    }

    private String getConnectionManagerScpdXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<actionList>" +
                "<action><name>GetProtocolInfo</name>" +
                "<argumentList>" +
                "<argument><name>Source</name><direction>out</direction><relatedStateVariable>ProtocolInfo</relatedStateVariable></argument>" +
                "<argument><name>Sink</name><direction>out</direction><relatedStateVariable>ProtocolInfo</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "</actionList>" +
                "<serviceStateTable>" +
                "<stateVariable><name>ProtocolInfo</name><dataType>string</dataType></stateVariable>" +
                "</serviceStateTable>" +
                "</scpd>";
    }

    private String getRenderingControlScpdXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<actionList>" +
                "<action><name>SetVolume</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>" +
                "<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "<action><name>GetVolume</name>" +
                "<argumentList>" +
                "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>" +
                "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>" +
                "<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>" +
                "</argumentList></action>" +
                "</actionList>" +
                "<serviceStateTable>" +
                "<stateVariable><name>Volume</name><dataType>ui2</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>" +
                "<stateVariable><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>" +
                "</serviceStateTable>" +
                "</scpd>";
    }

    private String getAVTransportControlXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getSetAVTransportURIResponseXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getPlayResponseXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getStopResponseXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:StopResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getPauseResponseXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:PauseResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getTransportInfoResponseXml() {
        String state = "STOPPED";
        String status = "OK";
        String speed = "1";
        
        // 根据当前状态返回正确的播放状态
        if (currentState == 1) {
            state = "PLAYING";
        } else if (currentState == 2) {
            state = "PAUSED_PLAYBACK";
        }
        
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<CurrentTransportState>" + state + "</CurrentTransportState>" +
                "<CurrentTransportStatus>" + status + "</CurrentTransportStatus>" +
                "<CurrentSpeed>" + speed + "</CurrentSpeed>" +
                "</u:GetTransportInfoResponse>" +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private String getPositionInfoResponseXml() {
        String trackDuration = formatTime(duration);
        String relTime = formatTime(currentPosition);
        Log.d(TAG, "GetPositionInfo requested, returning: position=" + currentPosition + "(" + relTime + "), duration=" + duration + "(" + trackDuration + ")");
        
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<Track>1</Track>" +
                "<TrackDuration>" + trackDuration + "</TrackDuration>" +
                "<RelTime>" + relTime + "</RelTime>" +
                "<AbsTime>0:00:00</AbsTime>" +
                "<RelCount>0</RelCount>" +
                "<AbsCount>0</AbsCount>" +
                "</u:GetPositionInfoResponse>" +
                "</s:Body>" +
                "</s:Envelope>";
    }


    private String getConnectionManagerControlXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body></s:Body></s:Envelope>";
    }

    private String getRenderingControlControlXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body></s:Body></s:Envelope>";
    }

    private void startAnnouncement() {
        executorService.execute(() -> {
            Log.d(TAG, "SSDP广播线程已启动");
            while (isServiceStarted) {
                try {
                    Log.d(TAG, "准备发送SSDP广播...");
                    DatagramSocket socket = new DatagramSocket();
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    Log.d(TAG, "创建组播地址: " + group.getHostAddress());
                    
                    String[] announceTypes = {
                        "upnp:rootdevice",
                        "uuid:test-device",
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "urn:schemas-upnp-org:service:ConnectionManager:1",
                        "urn:schemas-upnp-org:service:RenderingControl:1"
                    };
                    
                    for (String nt : announceTypes) {
                        String usn = "uuid:test-device";
                        if (!nt.equals("upnp:rootdevice") && !nt.equals("uuid:test-device")) {
                            usn = "uuid:test-device::" + nt;
                        }
                        
                        String notifyMessage = "NOTIFY * HTTP/1.1\r\n" +
                                "Host: 239.255.255.250:1900\r\n" +
                                "Location: http://" + localIpAddress + ":" + HTTP_PORT + "/device.xml\r\n" +
                                "NT: " + nt + "\r\n" +
                                "NTS: ssdp:alive\r\n" +
                                "USN: " + usn + "\r\n" +
                                "Cache-Control: max-age=1800\r\n" +
                                "Server: Android/7.0, UPnP/1.0, DLNA/1.50\r\n" +
                                "\r\n";
                        
                        Log.d(TAG, "准备发送广播消息类型: " + nt);
                        Log.d(TAG, "广播消息内容:\n" + notifyMessage);
                        
                        byte[] buffer = notifyMessage.getBytes();
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 1900);
                        socket.send(packet);
                        Log.d(TAG, "发送SSDP广播成功: " + nt);
                    }
                    
                    socket.close();
                    Log.d(TAG, "SSDP广播发送完成，等待下一次广播...");
                    
                    Thread.sleep(30000); // 30秒广播一次
                    
                } catch (Exception e) {
                    Log.e(TAG, "发送SSDP广播失败: " + e.getMessage());
                    e.printStackTrace();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Log.d(TAG, "SSDP广播线程已退出");
        });
    }

    private void startMulticastListener() {
        executorService.execute(() -> {
            try {
                Log.d(TAG, "开始监听组播...");
                
                MulticastSocket socket = new MulticastSocket(1900);
                Log.d(TAG, "创建组播socket成功，端口: 1900");
                
                InetAddress group = InetAddress.getByName("239.255.255.250");
                socket.joinGroup(group);
                Log.d(TAG, "加入组播组成功: " + group.getHostAddress());
                
                socket.setSoTimeout(5000);
                Log.d(TAG, "设置socket超时: 5000ms");
                
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                Log.d(TAG, "创建数据报文，缓冲区大小: " + buffer.length);
                
                while (isServiceStarted) {
                    try {
                        packet = new DatagramPacket(buffer, buffer.length);
                        Log.d(TAG, "等待接收组播消息...");
                        socket.receive(packet);
                        messageLength = packet.getLength();
                        System.arraycopy(packet.getData(), 0, messageBuffer, 0, messageLength);
                        String message = new String(messageBuffer, 0, messageLength);
                        Log.d(TAG, "收到组播消息，长度: " + messageLength + "字节，来源: " + packet.getAddress().getHostAddress() + ":" + packet.getPort());
                        Log.d(TAG, "消息内容:\n" + message);
                        
                        if (message.contains("M-SEARCH")) {
                            Log.d(TAG, "收到搜索请求，准备响应");
                            sendSearchResponse(socket, packet.getAddress(), packet.getPort());
                        }
                    } catch (SocketTimeoutException e) {
                        // 超时是正常的，继续监听
                    } catch (IOException e) {
                        Log.e(TAG, "组播接收失败: " + e.getMessage());
                        e.printStackTrace();
                        break;
                    }
                }
                
                socket.leaveGroup(group);
                socket.close();
                Log.d(TAG, "组播监听已关闭");
                
            } catch (Exception e) {
                Log.e(TAG, "启动组播监听失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void sendSearchResponse(MulticastSocket socket, InetAddress address, int port) {
        try {
            Log.d(TAG, "准备发送搜索响应，目标: " + address.getHostAddress() + ":" + port);
            String st = "urn:schemas-upnp-org:device:MediaRenderer:1";
            String usn = "uuid:test-device::urn:schemas-upnp-org:device:MediaRenderer:1";
            
            if (messageBuffer != null && messageBuffer.length > 0) {
                String message = new String(messageBuffer, 0, messageLength);
                Log.d(TAG, "解析搜索请求消息:\n" + message);
                
                if (message.contains("ST: upnp:rootdevice")) {
                    st = "upnp:rootdevice";
                    usn = "uuid:test-device::upnp:rootdevice";
                    Log.d(TAG, "匹配搜索类型: upnp:rootdevice");
                } else if (message.contains("ST: uuid:")) {
                    int stStart = message.indexOf("ST: uuid:") + 10;
                    int stEnd = message.indexOf("\r\n", stStart);
                    if (stEnd > stStart) {
                        st = "uuid:" + message.substring(stStart, stEnd);
                        usn = "uuid:test-device";
                        Log.d(TAG, "匹配搜索类型: uuid:" + message.substring(stStart, stEnd));
                    }
                } else if (message.contains("ST: urn:schemas-upnp-org:service:AVTransport")) {
                    st = "urn:schemas-upnp-org:service:AVTransport:1";
                    usn = "uuid:test-device::urn:schemas-upnp-org:service:AVTransport:1";
                    Log.d(TAG, "匹配搜索类型: AVTransport");
                } else if (message.contains("ST: urn:schemas-upnp-org:service:ConnectionManager")) {
                    st = "urn:schemas-upnp-org:service:ConnectionManager:1";
                    usn = "uuid:test-device::urn:schemas-upnp-org:service:ConnectionManager:1";
                    Log.d(TAG, "匹配搜索类型: ConnectionManager");
                } else if (message.contains("ST: urn:schemas-upnp-org:service:RenderingControl")) {
                    st = "urn:schemas-upnp-org:service:RenderingControl:1";
                    usn = "uuid:test-device::urn:schemas-upnp-org:service:RenderingControl:1";
                    Log.d(TAG, "匹配搜索类型: RenderingControl");
                } else {
                    Log.d(TAG, "使用默认搜索类型: MediaRenderer");
                }
            }
            
            String response = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "DATE: " + new java.util.Date().toString() + "\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://" + localIpAddress + ":" + HTTP_PORT + "/device.xml\r\n" +
                    "SERVER: Android/7.0, UPnP/1.0, DLNA/1.50\r\n" +
                    "ST: " + st + "\r\n" +
                    "USN: " + usn + "\r\n" +
                    "\r\n";
            
            Log.d(TAG, "准备发送响应消息:\n" + response);
            byte[] responseData = response.getBytes();
            Log.d(TAG, "响应消息长度: " + responseData.length + "字节");
            
            DatagramPacket responsePacket = new DatagramPacket(
                    responseData, responseData.length, address, port);
            socket.send(responsePacket);
            Log.d(TAG, "发送搜索响应成功, ST: " + st);
            
        } catch (Exception e) {
            Log.e(TAG, "发送搜索响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().equals("eth0") || intf.getName().equals("wlan0")) {
                    Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                    while (enumIpAddr.hasMoreElements()) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                            String ip = inetAddress.getHostAddress();
                            Log.d(TAG, "获取到设备IP地址: " + ip + ", 接口: " + intf.getName());
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP地址失败: " + e.getMessage());
        }
        Log.w(TAG, "无法获取IP地址，使用默认值");
        return "192.168.10.186";
    }

    public void play(String uri, String title) {
        Log.d(TAG, "播放: " + title + " - " + uri);
        currentUri = uri;
        currentTitle = title;
        currentState = 1;
        
        if (eventListener != null) {
            eventListener.onPlay(uri, title);
        }
    }

    public void pause() {
        Log.d(TAG, "暂停");
        currentState = 2;
        
        if (eventListener != null) {
            eventListener.onDlnaPause();
        }
    }

    public void stop() {
        Log.d(TAG, "停止");
        currentState = 0;
        
        if (eventListener != null) {
            eventListener.onDlnaStop();
        }
    }

    public void seek(long position) {
        Log.d(TAG, " seek to: " + position);
        currentPosition = position;
        if (eventListener != null) {
            eventListener.onSeek(position);
        }
    }

    public void updatePosition(long position, long totalDuration) {
        Log.d(TAG, "Received position update: " + position + ", duration: " + totalDuration);
        currentPosition = position;
        duration = totalDuration;
    }


    public void setVolume(int volume) {
        Log.d(TAG, "设置音量: " + volume);
        this.volume = volume;
        
        if (eventListener != null) {
            eventListener.onSetVolume(volume);
        }
    }

    public void setCurrentState(int state) {
        Log.d(TAG, "设置播放状态: " + state);
        this.currentState = state;
    }

    public int getCurrentState() {
        return this.currentState;
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                Notification notification = createNotificationWithText(text);
                manager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "更新通知失败: " + e.getMessage());
        }
    }

    private String extractTitle(String metaData) {
        if (metaData == null || metaData.isEmpty()) return "Unknown";
        try {
            int start = metaData.indexOf("<dc:title>");
            int end = metaData.indexOf("</dc:title>");
            if (start != -1 && end != -1) {
                return metaData.substring(start + 10, end);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析标题失败: " + e.getMessage());
        }
        return "Unknown";
    }

    private long parseTime(String time) {
        try {
            String[] parts = time.split(":");
            if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                return (hours * 3600 + minutes * 60 + seconds) * 1000;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析时间失败: " + e.getMessage());
        }
        return 0;
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DLNA Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return createNotificationWithText("正在运行，等待投屏...");
    }

    private Notification createNotificationWithText(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DLNA接收器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build();
    }
}
