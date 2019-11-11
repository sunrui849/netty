package sr.netty.test;

import io.netty.bootstrap.ServerBootstrap;
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
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.Date;

public class TimeServer {
    public void bind(int port) throws Exception{
        // 一个用于服务端接收客户端的连接，另一个用于进行socketChannel的网络读写
        // 用于处理连接请求和建立连接
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        // 处理IO请求
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            // 用于启动NIO服务端的辅助启动类，为了降低服务端的开发复杂度
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup,workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // 初始化服务端可连接队列，处理不过来的连接放到队列里等待处理
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childHandler(new ChildChannelHandler());
            // 绑定端口，同步等待绑定成功
            ChannelFuture f = b.bind(port).sync();
            // 等待服务端监听端口关闭
            f.channel().closeFuture().sync();
        } finally {
            // 优雅退出，释放线程池资源
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception{
        int port = 8080;
        new TimeServer().bind(port);
    }
}

class ChildChannelHandler extends ChannelInitializer<SocketChannel> {

    protected void initChannel(SocketChannel socketChannel) throws Exception {
        socketChannel.pipeline().addLast(new TimeServerHandler());
    }
}

class TimeServerHandler extends ChannelHandlerAdapter {
    // 读到消息
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception{
        ByteBuf buf = (ByteBuf) msg;
        byte[] req = new byte[buf.readableBytes()];
        buf.readBytes(req);
        String body = new String(req, "UTF-8");
        System.out.println("the time server receive order" + body);
        String currentTime = "QUERY TIME ORDER".equalsIgnoreCase(body) ? new Date(System.currentTimeMillis()).toString() : "BAD ORDER";
        ByteBuf resp = Unpooled.copiedBuffer(currentTime.getBytes());
        // 把待发送的消息放到发送缓冲数组中
        ctx.write(resp);
    }

    // 读完成,异常也会触发
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx){
        // 将发送缓冲区中的消息全部写到socketChannel中
        ctx.flush();
    }

    // 异常释放
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause){
        // 释放资源，关掉连接
        ctx.close();
        cause.printStackTrace();
    }
}
