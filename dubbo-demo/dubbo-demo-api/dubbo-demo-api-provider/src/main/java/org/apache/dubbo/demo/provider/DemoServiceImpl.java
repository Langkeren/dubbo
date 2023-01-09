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
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.DemoService;
import org.apache.dubbo.rpc.RpcContext;

import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * https://cn.dubbo.apache.org/zh/docsv2.7/dev/source/service-invoking-process/#21-%E6%9C%8D%E5%8A%A1%E8%B0%83%E7%94%A8%E6%96%B9%E5%BC%8F
 * Dubbo 默认使用 Javassist 框架为服务接口生成动态代理类
 *
 * 代理类的逻辑比较简单。首先将运行时参数存储到数组中，然后调用 {@link InvokerInvocationHandler}  接口实现类的 invoke 方法，得到调用结果，最后将结果转型并返回给调用方
 *
 */
public class DemoServiceImpl implements DemoService {
    private static final Logger logger = LoggerFactory.getLogger(DemoServiceImpl.class);

    @Override
    public String sayHello(String name) {
        logger.info("Hello " + name + ", request from consumer: " + RpcContext.getContext().getRemoteAddress());
        return "Hello " + name + ", response from provider: " + RpcContext.getContext().getLocalAddress();
    }

    @Override
    public CompletableFuture<String> sayHelloAsync(String name) {
        return null;
    }

}
