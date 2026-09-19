package io.doindev.codegraph.dba;

import io.lettuce.core.resource.NettyCustomizer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

/**
 * A Redis SCAN COUNT is only a hint, and keys can be enormous. Bound each isolated
 * operation connection before RESP decoding, including TLS wire bytes. No content is logged.
 */
final class NativeWireBudget implements NettyCustomizer {
    static final long MAX_BYTES=8L<<20;
    public void afterChannelInitialized(Channel channel){
        channel.pipeline().addFirst("codegraph-native-wire-budget",new Limit(MAX_BYTES));
        // This location is after TLS but before Lettuce can allocate from RESP length headers.
        var decoder=channel.pipeline().context(io.lettuce.core.protocol.CommandHandler.class);
        if(decoder==null)throw new IllegalStateException("Native Redis decoder guard could not be installed");
        channel.pipeline().addBefore(decoder.name(),"codegraph-native-resp-budget",new RespLimit());
    }
    static final class Limit extends ChannelInboundHandlerAdapter {
        private final long allowance;
        private long received;
        Limit(long allowance){this.allowance=allowance;}
        @Override public void channelRead(ChannelHandlerContext context,Object message){
            if(message instanceof ByteBuf buffer){
                received+=buffer.readableBytes();
                if(received>allowance){ReferenceCountUtil.release(message);context.close();return;}
            }
            context.fireChannelRead(message);
        }
    }

    /** Incremental RESP2/RESP3 envelope validation; never stores bulk contents. */
    static final class RespLimit extends ChannelInboundHandlerAdapter {
        private static final int MAX_DEPTH=32,MAX_CHILDREN=32768,MAX_VALUES=65536;
        private final int[] remaining=new int[MAX_DEPTH];
        private final StringBuilder line=new StringBuilder();
        private int depth,values,phase; // 0 prefix, 1 line, 2 bulk bytes, 3 CR, 4 LF
        private char type;
        private boolean lineCr;
        private long received,bulkRemaining;
        @Override public void channelRead(ChannelHandlerContext context,Object message){
            if(message instanceof ByteBuf buffer){
                received+=buffer.readableBytes();
                try{
                    if(received>MAX_BYTES)throw new IllegalArgumentException();
                    inspect(buffer);
                }catch(IllegalArgumentException invalid){
                    ReferenceCountUtil.release(message);context.close();return;
                }
            }
            context.fireChannelRead(message);
        }
        private void inspect(ByteBuf buffer){
            int cursor=buffer.readerIndex(),end=buffer.writerIndex();
            while(cursor<end){
                if(phase==2){
                    int take=(int)Math.min(bulkRemaining,end-cursor);cursor+=take;bulkRemaining-=take;
                    if(bulkRemaining==0)phase=3;continue;
                }
                int value=buffer.getUnsignedByte(cursor++);
                if(phase==0){
                    while(depth>0&&remaining[depth-1]==0)depth--;
                    if(depth>0)remaining[depth-1]--;
                    if(++values>MAX_VALUES)throw new IllegalArgumentException();
                    type=(char)value;if("+-:$*_%~>|!=,#(".indexOf(type)<0)throw new IllegalArgumentException();
                    line.setLength(0);lineCr=false;phase=1;
                }else if(phase==1){
                    if(lineCr){
                        if(value!='\n')throw new IllegalArgumentException();
                        completeLine();lineCr=false;
                    }else if(value=='\r')lineCr=true;
                    else{
                        if(value=='\n'||line.length()>=8192)throw new IllegalArgumentException();
                        line.append((char)value);
                    }
                }else if(phase==3){
                    if(value!='\r')throw new IllegalArgumentException();phase=4;
                }else{
                    if(value!='\n')throw new IllegalArgumentException();phase=0;
                }
            }
        }
        private void completeLine(){
            phase=0;
            if("$!=".indexOf(type)>=0){
                long length=length();
                if(length==-1)return;
                if(length>MAX_BYTES)throw new IllegalArgumentException();
                bulkRemaining=length;phase=length==0?3:2;
            }else if("*%~>|".indexOf(type)>=0){
                long length=length();if(length==-1)return;
                if(type=='%'||type=='|')length*=2;
                if(length>MAX_CHILDREN||depth>=MAX_DEPTH)throw new IllegalArgumentException();
                if(length>0)remaining[depth++]=(int)length;
            }else if(type=='_'&&line.length()!=0)throw new IllegalArgumentException();
        }
        private long length(){
            // Reject streamed/unknown-length aggregates and lengths that could overflow.
            if(line.length()==0||line.length()>10)throw new IllegalArgumentException();
            String value=line.toString();
            if(value.equals("-1"))return -1;
            if(!value.chars().allMatch(c->c>='0'&&c<='9'))throw new IllegalArgumentException();
            return Long.parseLong(value);
        }
    }
}
