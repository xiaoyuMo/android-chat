package cn.wildfirechat.proto;

import android.preference.PreferenceManager;

import com.alibaba.fastjson.JSON;
import com.comsince.github.client.AndroidNIOClient;
import com.comsince.github.client.PushMessageCallback;
import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.comsince.github.push.util.AES;
import com.comsince.github.push.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import cn.wildfirechat.proto.handler.ConnectAckMessageHandler;
import cn.wildfirechat.proto.handler.MessageHandler;
import cn.wildfirechat.proto.handler.RequestInfo;
import cn.wildfirechat.proto.handler.SearchUserResultMessageHandler;
import cn.wildfirechat.proto.model.ConnectMessage;

import static cn.wildfirechat.client.ConnectionStatus.ConnectionStatusConnected;
import static cn.wildfirechat.client.ConnectionStatus.ConnectionStatusUnconnected;
import static com.tencent.mars.comm.PlatformComm.context;

public class ProtoService implements PushMessageCallback {
    private static Log log = LoggerFactory.getLogger(ProtoService.class);
    private String userName;
    private String token;
    private AndroidNIOClient androidNIOClient;

    private List<MessageHandler> messageHandlers = new ArrayList<>();
    public ConcurrentHashMap<Integer, RequestInfo> requestMap = new ConcurrentHashMap<>();
    public ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    public ProtoService(){
        initHandlers();
    }

    public void connect(String host, int shortPort){
        androidNIOClient = new AndroidNIOClient(host,shortPort);
        androidNIOClient.setPushMessageCallback(this);
        androidNIOClient.connect();
    }

    private void initHandlers(){
        messageHandlers.add(new ConnectAckMessageHandler());
        messageHandlers.add(new SearchUserResultMessageHandler(this));
    }

    public void setUserName(String userName){
        this.userName = userName;
    }

    public void setToken(String token){
        this.token = token;
    }


    @Override
    public void receiveMessage(Header header, ByteBufferList byteBufferList) {
         for(MessageHandler messageHandler : messageHandlers){
             if(messageHandler.match(header)){
                 messageHandler.processMessage(header,byteBufferList);
                 break;
             }
         }
    }

    @Override
    public void receiveException(Exception e) {
        JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusUnconnected);
    }

    @Override
    public void onConnected() {
       JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusConnected);
       sendConnectMessage();
       scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
           @Override
           public void run() {
                androidNIOClient.heart(50*1000);
           }
       },10,30, TimeUnit.SECONDS);
    }

    private void sendConnectMessage(){
        ConnectMessage connectMessage = new ConnectMessage();
        connectMessage.setUserName(userName);
        byte[] byteToken = Base64.decode(token);
        byte[] aesToken = AES.AESDecrypt(byteToken,"",true);
        String allToken = new String(aesToken);

        String pwd = allToken.substring(0,allToken.indexOf("|"));
        allToken = allToken.substring(allToken.indexOf("|")+1);
        String secret = allToken.substring(0,allToken.indexOf("|"));
        log.i("pwd->"+pwd+ " secret-> "+secret);
        //利用secret加密pwd
        byte[] pwdAES = AES.AESEncrypt(Base64.decode(pwd),secret);
        log.i("base64 pwd encrypt->"+Base64.encode(pwdAES));
        connectMessage.setPassword(Base64.encode(pwdAES));
        connectMessage.setClientIdentifier(PreferenceManager.getDefaultSharedPreferences(context).getString("mars_core_uid", ""));
        log.i("send connectMessage "+JSON.toJSONString(connectMessage));
        sendMessage(Signal.CONNECT,SubSignal.NONE, JSON.toJSONString(connectMessage).getBytes(),null);
    }

    private void sendMessage(Signal signal,SubSignal subSignal,byte[] message,Object callback){
        scheduledExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                int messageId = PreferenceManager.getDefaultSharedPreferences(context).getInt("message_id",0);
                log.i("send message id "+messageId + callback);
                androidNIOClient.sendMessage(signal, subSignal,messageId, message);
                if(callback != null){
                    RequestInfo requestInfo = new RequestInfo(signal,subSignal,callback.getClass(),callback);
                    requestMap.put(messageId,requestInfo);
                }
                PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("message_id",++messageId).apply();
            }
        });

    }

    public void searchUser(String keyword, JavaProtoLogic.ISearchUserCallback callback){
        WFCMessage.SearchUserRequest request = WFCMessage.SearchUserRequest.newBuilder()
                .setKeyword(keyword)
                .setFuzzy(1)
                .setPage(0)
                .build();
        sendMessage(Signal.PUBLISH,SubSignal.US,request.toByteArray(),callback);
    }
}