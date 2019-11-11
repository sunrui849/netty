测试demo：sr.netty.test
* NioEventLoopGroup 默认线程数： CPU个数 * 2
* 连接线程池，每建立一个连接，都会通过线程池启动一个无线循环的任务, 简化为下面代码块
```
scheduleExecution(){
    executor.execute(asRunnable);
}
asRunnable(){
    run(){
        selector.selectedKeys();
        scheduleExecution();
    }
}
```
* 工作线程池，每当连接建立成功后，会将channel注册到工作线程池中的某个线程对应的selector上
```
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        childGroup.register(child)
    }
```
* 在 initAndRegister() 中的init()中将childGroup传入 ServerBootstrapAcceptor 中，前一步通过该步骤的初始化，才具有工作线程的能力