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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.codec.ExchangeCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DECODE_IN_IO_THREAD_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_DECODE_IN_IO_THREAD;

/**
 * Dubbo codec.
 */
public class DubboCodec extends ExchangeCodec {

    public static final String NAME = "dubbo";
    public static final String DUBBO_VERSION = Version.getProtocolVersion();
    public static final byte RESPONSE_WITH_EXCEPTION = 0;
    public static final byte RESPONSE_VALUE = 1;
    public static final byte RESPONSE_NULL_VALUE = 2;
    public static final byte RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    public static final byte RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    public static final byte RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        // 获取请求编号
        long id = Bytes.bytes2long(header, 4);
        // 检测消息类型，若下面的条件成立，表明消息类型为 Response
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            // 创建 Response 对象
            Response res = new Response(id);
            // 检测事件标志位
            if ((flag & FLAG_EVENT) != 0) {
                // 设置心跳事件
                res.setEvent(true);
            }
            // get status.
            // 获取响应状态
            byte status = header[3];
            // 设置响应状态
            res.setStatus(status);
            try {
                // 如果响应状态为 OK，表明调用过程正常
                if (status == Response.OK) {
                    Object data;
                    if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                            // heart beat response data is always null;
                            data = null;
                        } else {
                            ObjectInput in = CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto);
                            data = decodeEventData(channel, in, eventPayload);
                        }
                    } else {
                        DecodeableRpcResult result;
                        // 根据 url 参数决定是否在 IO 线程上执行解码逻辑
                        if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                            // 创建 DecodeableRpcResult 对象
                            result = new DecodeableRpcResult(channel, res, is,
                                    (Invocation) getRequestData(id), proto);
                            // 进行后续的解码工作
                            result.decode();
                        } else {
                            // 创建 DecodeableRpcResult 对象
                            result = new DecodeableRpcResult(channel, res,
                                    new UnsafeByteArrayInputStream(readMessageData(is)),
                                    (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }

                    // 设置 DecodeableRpcResult 对象到 Response 对象中
                    res.setResult(data);
                } else {
                    // 解码过程中出现了错误，此时设置 CLIENT_ERROR 状态码到 Response 对象中
                    ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // 对请求数据进行解码，前面已分析过，此处忽略
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                Object data;
                if (req.isEvent()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    if (CodecSupport.isHeartBeat(eventPayload, proto)) {
                        // heart beat response data is always null;
                        data = null;
                    } else {
                        ObjectInput in = CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto);
                        data = decodeEventData(channel, in, eventPayload);
                    }
                } else {
                    DecodeableRpcInvocation inv;
                    if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                        inv = new DecodeableRpcInvocation(channel, req, is, proto);
                        inv.decode();
                    } else {
                        inv = new DecodeableRpcInvocation(channel, req,
                                new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }
                    data = inv;
                }
                req.setData(data);
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }
                // bad request
                req.setBroken(true);
                req.setData(t);
            }

            return req;
        }
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        RpcInvocation inv = (RpcInvocation) data;

        out.writeUTF(version);
        // https://github.com/apache/dubbo/issues/6138
        String serviceName = inv.getAttachment(INTERFACE_KEY);
        if (serviceName == null) {
            serviceName = inv.getAttachment(PATH_KEY);
        }
        out.writeUTF(serviceName);
        out.writeUTF(inv.getAttachment(VERSION_KEY));

        out.writeUTF(inv.getMethodName());
        out.writeUTF(inv.getParameterTypesDesc());
        Object[] args = inv.getArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                out.writeObject(encodeInvocationArgument(channel, inv, i));
            }
        }
        out.writeAttachments(inv.getObjectAttachments());
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        Result result = (Result) data;
        // currently, the version value in Response records the version of Request
        // 检测当前协议版本是否支持带有 attachment 集合的 Response 对象
        boolean attach = Version.isSupportResponseAttachment(version);
        Throwable th = result.getException();

        // 异常信息为空
        if (th == null) {
            Object ret = result.getValue();
            // 调用结果为空
            if (ret == null) {
                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);
                // 调用结果非空
            } else {
                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                // 序列化调用结果
                out.writeObject(ret);
            }
        } else {
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            out.writeThrowable(th);
        }

        if (attach) {
            // returns current version of Response to consumer side.
            // 记录 Dubbo 协议版本
            result.getObjectAttachments().put(DUBBO_VERSION_KEY, Version.getProtocolVersion());
            // 序列化 attachments 集合
            out.writeAttachments(result.getObjectAttachments());
        }
    }

    @Override
    protected Serialization getSerialization(Channel channel, Request req) {
        if (!(req.getData() instanceof Invocation)) {
            return super.getSerialization(channel, req);
        }
        return DubboCodecSupport.getRequestSerialization(channel.getUrl(), (Invocation) req.getData());
    }

    @Override
    protected Serialization getSerialization(Channel channel, Response res) {
        if (!(res.getResult() instanceof AppResponse)) {
            return super.getSerialization(channel, res);
        }
        return DubboCodecSupport.getResponseSerialization(channel.getUrl(), (AppResponse) res.getResult());
    }

}
