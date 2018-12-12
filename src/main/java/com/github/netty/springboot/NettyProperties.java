package com.github.netty.springboot;

import com.github.netty.core.util.ApplicationX;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.Serializable;
import java.util.concurrent.Executor;

/**
 * 在这里可以进行优化操作
 * @author acer01
 * 2018/8/25/025
 *
 *  1.优化记录 (2018年8月25日 22:16:13),
 *      发现问题 : rpc调用不论设置超时时间多长, 总是会超时.
 *                  而且不会因为客户端的连接数增大而改善,反而降低性能, 1个线程=400qps, 100个线程=100-250qps
 *                  而且服务端只要不发生gc,执行时间都是在3纳秒以内
 *                  超时时间=10秒, 100个客户端线程, 108750次调用, 80次超时 = 0.027%的超时几率, 100-250的qps
 *
 *         猜测1 : 估计是客户端的原因, 客户端的执行方法, 有堵塞的调用, 而且堵塞范围过广, 可以从缩小堵塞的影响范围入手
 *         猜测2 : 网络丢包, 因为请求后, 没有发现有响应对应的请求id
 *
 *         猜测3 : id生成问题, 因为测出以下结果, ID(63604)还未请求 就已经先响应
             2018-08-26 00:35:55.359 ERROR 6688 --- [rkerGroup@1-4-1] c.g.n.s.impl.RemoteSessionServiceImpl    : 1 save requestTimeout : maxTimeout is [10000]
             2018-08-26 00:36:09.878 ERROR 6688 --- [rkerGroup@3-5-1] c.g.n.c.rpc.RpcClient$RpcClientHandler   : 超时的响应[] : null
                                                                                                                 :requestId: 63604
                                                                                                                 status: 200
                                                                                                                 message: "ok"
                                                                                                                 encode: 1

             2018-08-26 00:36:09.879 ERROR 6688 --- [rkerGroup@3-5-1] c.g.n.c.rpc.RpcClient$RpcClientHandler   : 超时的响应[] : null
                                                                                                                 :requestId: 63605
                                                                                                                 status: 200
                                                                                                                 message: "ok"
                                                                                                                 encode: 1

             2018-08-26 00:36:19.878 ERROR 6688 --- [rkerGroup@1-4-2] c.g.n.s.impl.RemoteSessionServiceImpl    : 超时的请求 : requestId: 63604
                                                                                                                 serviceName: "/_inner/db"
                                                                                                                 methodName: "get"
                                                                                                                 data: "[\"dc177356b505447cbf3338537a90f27a\"]"

             2018-08-26 00:36:19.878 ERROR 6688 --- [rkerGroup@1-4-2] c.g.n.s.impl.RemoteSessionServiceImpl    : 2 get requestTimeout : maxTimeout is [10000]
             2018-08-26 00:36:19.880 ERROR 6688 --- [rkerGroup@1-4-1] c.g.n.s.impl.RemoteSessionServiceImpl    : 超时的请求 : requestId: 63605
                                                                                                                 serviceName: "/_inner/db"
                                                                                                                 methodName: "get"
                                                                                                                data: "[\"d7cc6c7a8f7f4c9792c56ee8b472ec6d\"]"
            猜测4 : 思路 : 1.如果客户端不发送请求, 服务端是不可能响应的
                           2.从发送请求的地方寻找线索,
                           3.客户端判断服务端超时的依据是 : 服务端响应后, 如果获取不到该请求的锁, 则判定为超时
                           4.那么会不会是因为客户端的锁还没放进去, 服务端就响应了
                           5.于是调换执行顺序, 先放锁, 再发送请求
                           6.验证成功! 就是这个原因 ( : 看来有时rpc的执行速度比map.put要快)

        解决问题 (已解决超时情况):
            原因1. : 旧:  RpcLock lock = new RpcLock();
                        getSocketChannel().writeAndFlush(rpcRequest); 这里发送早了,锁还没放进去, 就响应了, 导致响应拿不到锁
                        requestLockMap.put(requestId,lock);

                   新:  RpcLock lock = new RpcLock();
                       requestLockMap.put(requestId,lock);  先放锁, 再请求, 问题解决
                       getSocketChannel().writeAndFlush(rpcRequest);

            原因2 : session服务端的jvm内存不足, 导致大量gc,
                    参数改为后 : -Xms600m -Xmn600m -Xmx1000m

            验证 : 测试结果
                        第(100)次统计, 时间 = 500001毫秒[8分20秒], 总调用次数 = 1435015, 总超时次数 = 0, 成功率 = 100
                        第(387)次统计, 时间 = 1935001毫秒[32分15秒], 总调用次数 = 4314961, 总超时次数 = 8, 成功率 = 100.00 (注 : 这个是因为session服务端的内存满了)
                            session服务端gc情况 : [Full GC (Allocation Failure) [Tenured: 409600K->409599K(409600K), 1.7072878 secs] 962559K->620546K(962560K), [Metaspace: 9959K->9959K(10624K)], 1.7075061 secs] [Times: user=1.70 sys=0.00, real=1.71 secs]

                        30分钟, 143万次请求, 431万次rpc调用, 8次rpc超时, 服务端保存143万个会话
                        配置参数 :   getClientEventLoopWorkerCount = 3
                                     getServerEventLoopWorkerCount = 2
                                     getServerEventLoopIoRatio=100
                                     getClientEventLoopIoRatio=100
                                     getSessionClientSocketChannelCount=1
                                     isSessionClientEnableAutoReconnect=true
                                     isEnableExecuteHold=false
                                     isEnableLog=true
                                    RpcDBService.timeout=1000毫秒

        新的问题 :
            qps 还是一直在1000左右,
        下面是测试结果
         15:07:30.720 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(1)次统计, 时间 = 5002毫秒[0分5秒], 成功 = 2843, 失败 = 0, qps = 568.37
         15:07:35.720 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(2)次统计, 时间 = 10022毫秒[0分10秒], 成功 = 9421, 失败 = 0, qps = 940.03
         15:07:40.721 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(3)次统计, 时间 = 14723毫秒[0分14秒], 成功 = 15178, 失败 = 0, qps = 1030.90
         15:07:45.761 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(4)次统计, 时间 = 19463毫秒[0分19秒], 成功 = 21329, 失败 = 0, qps = 1095.87
         15:07:55.762 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(6)次统计, 时间 = 29164毫秒[0分29秒], 成功 = 32479, 失败 = 0, qps = 1113.67
         15:08:00.762 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(7)次统计, 时间 = 34164毫秒[0分34秒], 成功 = 39302, 失败 = 0, qps = 1150.39
         15:08:20.765 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(11)次统计, 时间 = 53267毫秒[0分53秒], 成功 = 62640, 失败 = 0, qps = 1175.96
         15:08:25.765 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(12)次统计, 时间 = 58267毫秒[0分58秒], 成功 = 67813, 失败 = 0, qps = 1163.83
         15:08:55.768 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(18)次统计, 时间 = 87370毫秒[1分27秒], 成功 = 98933, 失败 = 0, qps = 1132.35
         15:09:40.772 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(27)次统计, 时间 = 130574毫秒[2分10秒], 成功 = 151317, 失败 = 0, qps = 1158.86
         15:11:30.787 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(49)次统计, 时间 = 237289毫秒[3分57秒], 成功 = 264122, 失败 = 0, qps = 1113.08
         15:14:10.828 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(81)次统计, 时间 = 392830毫秒[6分32秒], 成功 = 414227, 失败 = 0, qps = 1054.47
         15:15:20.836 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(95)次统计, 时间 = 460738毫秒[7分40秒], 成功 = 462517, 失败 = 0, qps = 1003.86
         15:15:40.838 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(99)次统计, 时间 = 480140毫秒[8分0秒], 成功 = 479827, 失败 = 0, qps = 999.35
         15:17:40.849 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(123)次统计, 时间 = 596551毫秒[9分56秒], 成功 = 562187, 失败 = 0, qps = 942.40
         15:18:40.856 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(135)次统计, 时间 = 654758毫秒[10分54秒], 成功 = 611696, 失败 = 0, qps = 934.23
         15:21:45.884 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(172)次统计, 时间 = 834686毫秒[13分54秒], 成功 = 762595, 失败 = 0, qps = 913.63
         15:38:18.034 [QpsPrintThread] INFO QpsRunningTest$PrintThread - 第(350)次统计, 时间 = 1801035毫秒[30分1秒], 成功 = 1438254, 失败 = 0, qps = 798.57
                     Exception in thread "vert.x-eventloop-thread-1" java.lang.OutOfMemoryError: Java heap space
                     java.lang.OutOfMemoryError: Java heap space
                     java.lang.OutOfMemoryError: Java heap space
                     java.lang.OutOfMemoryError: Java heap space
                     java.lang.OutOfMemoryError: Java heap space
 *
 *
 * 2.优化记录(2018年8月26日 15:34:04)
 *     发现问题 : 希望可以将qps优化到5000+, 目前是1000,而且还不稳定 像本地方法调用一样快速(本地方法qps是5000+)
 *      入手点1 : 优化RpcLock.lock方法 -> 原因 : 通过jvisualvm工具发现 RpcLock.lock方法居然占用并堵塞唯一2条线程60%的工作时间
 *
 *          实现方式1 : 在lock的实现方法中,增加了自旋, 如果自旋后没有获取到响应, 再进行堵塞 (不过几乎看不到效果,)
 *              测试结果 : 第75次统计, 时间=6分15秒, 调用数=1030483, 自旋成功数=138, 自旋成功率=0.01
 *
 *
 */
@ConfigurationProperties(prefix = "server.netty", ignoreUnknownFields = true)
public class NettyProperties implements Serializable{
    private static final long serialVersionUID = 1L;

    /**
     * 服务端-IO线程数  注: (0 = cpu核数 * 2 )
     */
    private int serverIoThreads = 0;

    /**
     * 服务端 - servlet线程执行器
     */
    private Executor serverHandlerExecutor = null;

    /**
     * 服务端-io线程执行调度与执行io事件的百分比. 注:(100=每次只执行一次调度工作, 其他都执行io事件), 并发高的时候可以设置最大
     */
    private int serverIoRatio = 100;

    /**
     * RPC客户端-工作线程数   注: (0 = cpu核数 * 2 )
     */
    private int rpcClientIoThreads = 1;

    /**
     * RPC客户端-IO线程执行调度与执行io事件的百分比. 注:(100=每次只执行一次调度工作, 其他都执行io事件), 并发高的时候可以设置最大
     */
    private int rpcClientIoRatio = 100;

    /**
     * RPC客户端-RPC同步调用超时时间
     */
    private int rpcTimeout = 1000;

    /**
     * RPC客户端 - 保持的连接数
     */
    private int rpcClientChannels = 1;

    /**
     * RPC客户端-是否RPC开启心跳日志
     */
    private boolean enableRpcHeartLog = false;

    /**
     * RPC客户端 - 自动重连
     */
    private boolean enablesRpcClientAutoReconnect = true;

    /**
     * RPC客户端 - 心跳间隔时间(秒)
     */
    private int rpcClientHeartIntervalSecond = 20;
    /**
     * RPC服务端 - 每次消息最大长度 (默认2M)
     */
    private int rpcServerMessageMaxLength = 2 * 1024 * 1024;
    /**
     * session存储 - 是否开启本地文件存储
     */
    private boolean enablesLocalFileSession = false;

    /**
     * session存储 - session远程存储的url地址, 注: 如果不设置就不会开启
     */
    private String sessionRemoteServerAddress;

    /**
     * 每次调用servlet的 OutputStream.Writer()方法写入的最大堆字节,超出后用堆外内存
     */
    private int responseWriterChunkMaxHeapByteLength = 4096;

    /**
     * 全局对象
     */
    private ApplicationX application = new ApplicationX();

    public ApplicationX getApplication() {
        return application;
    }

    public void setApplication(ApplicationX application) {
        this.application = application;
    }

    public int getRpcClientHeartIntervalSecond() {
        return rpcClientHeartIntervalSecond;
    }

    public void setRpcClientHeartIntervalSecond(int rpcClientHeartIntervalSecond) {
        this.rpcClientHeartIntervalSecond = rpcClientHeartIntervalSecond;
    }

    public boolean isEnablesLocalFileSession() {
        return enablesLocalFileSession;
    }

    public void setEnablesLocalFileSession(boolean enablesLocalFileSession) {
        this.enablesLocalFileSession = enablesLocalFileSession;
    }

    public int getRpcTimeout() {
        return rpcTimeout;
    }

    public void setRpcTimeout(int rpcTimeout) {
        this.rpcTimeout = rpcTimeout;
    }

    public int getResponseWriterChunkMaxHeapByteLength() {
        return responseWriterChunkMaxHeapByteLength;
    }

    public void setResponseWriterChunkMaxHeapByteLength(int responseWriterChunkMaxHeapByteLength) {
        this.responseWriterChunkMaxHeapByteLength = responseWriterChunkMaxHeapByteLength;
    }

    public int getRpcClientIoThreads() {
        return rpcClientIoThreads;
    }

    public void setRpcClientIoThreads(int rpcClientIoThreads) {
        this.rpcClientIoThreads = rpcClientIoThreads;
    }

    public int getServerIoThreads() {
        return serverIoThreads;
    }

    public void setServerIoThreads(int serverIoThreads) {
        this.serverIoThreads = serverIoThreads;
    }

    public int getServerIoRatio() {
        return serverIoRatio;
    }

    public void setServerIoRatio(int serverIoRatio) {
        this.serverIoRatio = serverIoRatio;
    }

    public int getRpcClientIoRatio() {
        return rpcClientIoRatio;
    }

    public void setRpcClientIoRatio(int rpcClientIoRatio) {
        this.rpcClientIoRatio = rpcClientIoRatio;
    }

    public int getRpcClientChannels() {
        return rpcClientChannels;
    }

    public void setRpcClientChannels(int rpcClientChannels) {
        this.rpcClientChannels = rpcClientChannels;
    }

    public boolean isEnablesRpcClientAutoReconnect() {
        return enablesRpcClientAutoReconnect;
    }

    public void setEnablesRpcClientAutoReconnect(boolean enablesRpcClientAutoReconnect) {
        this.enablesRpcClientAutoReconnect = enablesRpcClientAutoReconnect;
    }

    public Executor getServerHandlerExecutor() {
        return serverHandlerExecutor;
    }

    public void setServerHandlerExecutor(Executor serverHandlerExecutor) {
        this.serverHandlerExecutor = serverHandlerExecutor;
    }

    public String getSessionRemoteServerAddress() {
        return sessionRemoteServerAddress;
    }

    public void setSessionRemoteServerAddress(String sessionRemoteServerAddress) {
        this.sessionRemoteServerAddress = sessionRemoteServerAddress;
    }

    public boolean isEnableRpcHeartLog() {
        return enableRpcHeartLog;
    }

    public void setEnableRpcHeartLog(boolean enableRpcHeartLog) {
        this.enableRpcHeartLog = enableRpcHeartLog;
    }

    public int getRpcServerMessageMaxLength() {
        return rpcServerMessageMaxLength;
    }

    public void setRpcServerMessageMaxLength(int rpcServerMessageMaxLength) {
        this.rpcServerMessageMaxLength = rpcServerMessageMaxLength;
    }

    @Override
    public String toString() {
        return "NettyProperties{" +
                "serverWorkerCount=" + serverIoThreads +
                ", serverIoRatio=" + serverIoRatio +
                ", rpcClientWorkerCount=" + rpcClientIoThreads +
                ", rpcClientIoRatio=" + rpcClientIoRatio +
                ", rpcTimeout=" + rpcTimeout +
                ", rpcClientChannelCount=" + rpcClientChannels +
                ", enablesRpcClientAutoReconnect=" + enablesRpcClientAutoReconnect +
                ", rpcClientHeartIntervalSecond=" + rpcClientHeartIntervalSecond +
                ", sessionRemoteServerAddress='" + sessionRemoteServerAddress + '\'' +
                ", responseWriterChunkMaxHeapByteLength=" + responseWriterChunkMaxHeapByteLength +
                ", rpcServerMessageMaxLength=" + rpcServerMessageMaxLength +
                '}';
    }
}
