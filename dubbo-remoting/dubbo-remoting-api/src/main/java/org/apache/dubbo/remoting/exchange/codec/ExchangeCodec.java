/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Response 对象的编码
 * ExchangeCodec.
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    // 消息头长度
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    // 魔数内容
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    protected static final byte FLAG_EVENT = (byte) 0x20;
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            // 对 Request 对象进行编码
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            // 对 Response 对象进行编码，后面分析
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        // 创建消息头字节数组
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        // 读取消息头数据
        buffer.readBytes(header);
        // 调用重载方法进行后续解码工作
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 检查魔数是否相等
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // 通过 telnet 命令行发送的数据包不包含消息头，所以这里
            // 调用 TelnetCodec 的 decode 方法对数据包进行解码
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        // 检测可读数据量是否少于消息头长度，若小于则立即返回 DecodeResult.NEED_MORE_INPUT
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        // 从消息头中获取消息体长度
        int len = Bytes.bytes2int(header, 12);

        // When receiving response, how to exceed the length, then directly construct a response to the client.
        // see more detail from https://github.com/apache/dubbo/issues/7021.
        Object obj = finishRespWhenOverPayload(channel, len, header);
        if (null != obj) {
            return obj;
        }

        // 检测消息体长度是否超出限制，超出则抛出异常
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        // 检测可读的字节数是否小于实际的字节数
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 继续进行解码工作
            return decodeBody(channel, is, header);
        } finally {
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 获取消息头中的第三个字节，并通过逻辑与运算得到序列化器编号
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        // 获取调用编号
        long id = Bytes.bytes2long(header, 4);
        // 通过逻辑与运算得到调用类型，0 - Response，1 - Request
        if ((flag & FLAG_REQUEST) == 0) {
            // 对响应结果进行解码，得到 Response 对象。这个非本节内容，后面再分析
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                if (status == Response.OK) {
                    Object data;
                    if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                            // heart beat response data is always null;
                            data = null;
                        } else {
                            data = decodeEventData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                        }
                    } else {
                        data = decodeResponseData(channel, CodecSupport.deserialize(channel.getUrl(), is, proto), getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(CodecSupport.deserialize(channel.getUrl(), is, proto).readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            // 创建 Request 对象
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            // 通过逻辑与运算得到通信方式，并设置到 Request 对象中
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 通过位运算检测数据包是否为事件类型
            if ((flag & FLAG_EVENT) != 0) {
                // 设置心跳事件到 Request 对象中
                req.setEvent(true);
            }
            try {
                Object data;
                if (req.isEvent()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                        // 对心跳包进行解码，该方法已被标注为废弃
                        // heart beat response data is always null;
                        data = null;
                    } else {
                        // 对事件数据进行解码
                        data = decodeEventData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    }
                } else {
                    data = decodeRequestData(channel, CodecSupport.deserialize(channel.getUrl(), is, proto));
                }
                // 设置 data 到 Request 对象中
                req.setData(data);
            } catch (Throwable t) {
                // 若解码过程中出现异常，则将 broken 字段设为 true，
                // 并将异常对象设置到 Reqeust 对象中
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }

    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel, req);
        // header.
        // 创建消息头字节数组，长度为 16
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // 设置魔数
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 设置数据包类型（Request/Response）和序列化器编号
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 设置通信方式(单向/双向)
        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;
        }

        // 设置事件标识
        if (req.isEvent()) {
            header[2] |= FLAG_EVENT;
        }

        // set request id.
        // 设置请求编号，8个字节，从第4个字节开始设置
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 获取 buffer 当前的写位置
        int savedWriteIndex = buffer.writerIndex();
        // 更新 writerIndex，为消息头预留 16 个字节的空间
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);

        if (req.isHeartbeat()) {
            // heartbeat request data is always null
            bos.write(CodecSupport.getNullBytesOf(serialization));
        } else {
            // 创建序列化器，比如 Hessian2ObjectOutput
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            if (req.isEvent()) {
                // 对事件数据进行序列化操作
                encodeEventData(channel, out, req.getData());
            } else {
                // 对请求数据进行序列化操作
                encodeRequestData(channel, out, req.getData(), req.getVersion());
            }
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
        }

        bos.flush();
        bos.close();
        // 获取写入的字节数，也就是消息体长度
        int len = bos.writtenBytes();
        checkPayload(channel, len);

        // 将消息体长度写入到消息头中
        Bytes.int2bytes(len, header, 12);

        // write
        // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
        buffer.writerIndex(savedWriteIndex);
        buffer.writeBytes(header); // write header.
        // 设置新的 writerIndex，writerIndex = 原写下标 + 消息头长度 + 消息体长度
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel, res);
            // header.
            // 创建消息头字节数组
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            // 设置魔数
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            // 设置序列化器编号
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }
            // set response status.
            // 获取响应状态
            byte status = res.getStatus();
            // 设置响应状态
            header[3] = status;
            // set request id.
            // 设置请求编号
            Bytes.long2bytes(res.getId(), header, 4);

            // 更新 writerIndex，为消息头预留 16 个字节的空间
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);

            // encode response data or error message.
            if (status == Response.OK) {
                if(res.isHeartbeat()){
                    // heartbeat response data is always null
                    bos.write(CodecSupport.getNullBytesOf(serialization));
                }else {
                    ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
                    if (res.isEvent()) {
                        encodeEventData(channel, out, res.getResult());
                    } else {
                        encodeResponseData(channel, out, res.getResult(), res.getVersion());
                    }
                    out.flushBuffer();
                    if (out instanceof Cleanable) {
                        ((Cleanable) out).cleanup();
                    }
                }
            } else {
                ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
                // 对错误信息进行序列化
                out.writeUTF(res.getErrorMessage());
                out.flushBuffer();
                if (out instanceof Cleanable) {
                    ((Cleanable) out).cleanup();
                }
            }

            bos.flush();
            bos.close();

            // 获取写入的字节数，也就是消息体长度
            int len = bos.writtenBytes();
            checkPayload(channel, len);
            // 将消息体长度写入到消息头中
            Bytes.int2bytes(len, header, 12);
            // write
            // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
            buffer.writerIndex(savedWriteIndex);
            // 从 savedWriteIndex 下标处写入消息头
            buffer.writeBytes(header); // write header.
            // 设置新的 writerIndex，writerIndex = 原写下标 + 消息头长度 + 消息体长度
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // 异常处理逻辑不是很难理解，但是代码略多，这里忽略了
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeEvent(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in, byte[] eventBytes) throws IOException {
        try {
            if (eventBytes != null) {
                int dataLen = eventBytes.length;
                int threshold = ConfigurationUtils.getSystemConfiguration().getInt("deserialization.event.size", 50);
                if (dataLen > threshold) {
                    throw new IllegalArgumentException("Event data too long, actual size " + dataLen + ", threshold " + threshold + " rejected for security consideration.");
                }
            }
            return in.readEvent();
        } catch (IOException | ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Decode dubbo protocol event failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }

    private Object finishRespWhenOverPayload(Channel channel, long size, byte[] header) {
        int payload = getPayload(channel);
        boolean overPayload = isOverPayload(payload, size);
        if (overPayload) {
            long reqId = Bytes.bytes2long(header, 4);
            byte flag = header[2];
            if ((flag & FLAG_REQUEST) == 0) {
                Response res = new Response(reqId);
                if ((flag & FLAG_EVENT) != 0) {
                    res.setEvent(true);
                }
                res.setStatus(Response.CLIENT_ERROR);
                String errorMsg = "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel;
                logger.error(errorMsg);
                res.setErrorMessage(errorMsg);
                return res;
            }
        }
        return null;
    }
}
