package sr.netty.test;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class TimeClient {
    public void connect(String host, int port) throws Exception{
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class)
                    // 即时将消息发送出去，而不是等到多个小包组合成一个大包一起发送，可以降低延时，
                    // TCP_CORK则是将多个小包组合成大包发送，有效提高网络的有效负载，但是造成了消息延时
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new TimeClientHandler());
                        }
                    });
            // 发起同步连接操作
            ChannelFuture future = bootstrap.connect(host, port).sync();
            // 阻塞等待客户端链路关闭
            future.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception{
        int port = 8080;
        new TimeClient().connect("127.0.0.1", port);
    }
}

class TimeClientHandler extends ChannelHandlerAdapter {
    private final ByteBuf firstMessage;

    public TimeClientHandler(){
        // 初始化消息
        byte[] req = "QUERY TIME ORDER".getBytes();
        firstMessage = Unpooled.buffer(req.length);
        firstMessage.writeBytes(req);
    }

    // 建立连接成功后回调
    @Override
    public void channelActive(ChannelHandlerContext ctx){
        // 将请求消息发送给服务端
        ctx.writeAndFlush(firstMessage);
    }

    // 读到消息回调
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception{
        ByteBuf buf = (ByteBuf) msg;
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        String body = new String(bytes, "UTF-8");
        System.out.println("Now is:" + body);
    }

    // 发生异常回调
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause){
        cause.printStackTrace();
        // 释放资源，关掉连接
        ctx.close();
    }
}