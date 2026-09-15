/*
 * Copyright (c) 2024-present ypbin-iot-starter authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.iot.core.util;

import cn.ypbin.iot.core.exception.IotException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * {@link CompletionStage} 异常交付工具。
 *
 * <p><b>为什么需要它</b>：{@link CompletableFuture} 的组合算子（{@code thenApply}、
 * {@code whenComplete} 等）在向上游异常时会用 {@link CompletionException} 包装原始异常。
 * 这意味着调用方 {@code catch (ConnectionException ex)} <b>捕获不到</b>，
 * 必须先解包——对宿主而言这是极不友好的契约。</p>
 *
 * <p>本仓对外承诺：<b>所有 SPI 方法异常完成时交付的是原始领域异常
 * （{@link cn.ypbin.iot.core.exception.IotException} 子类），而不是 CompletionException 包装</b>。
 * 实现方式是在异常路径上显式 {@code completeExceptionally(原始异常)}，
 * 而不是让组合算子自行传播。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class Stages {

    private Stages() {
    }

    /**
     * 逐层解包 {@link CompletionException} / {@link ExecutionException}。
     *
     * @param throwable 原始异常
     * @return 最内层的业务异常；无可解包内容时返回原异常
     */
    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 从异常中提取消息键，用于把失败原因<b>原样</b>带出去。
     *
     * <p>典型场景是 {@code probe()}：把 TLS 未实现、安全策略不支持这类配置错误
     * 一律折叠成「端点不可达」，会让「测试连接」这个最常用的诊断入口失去价值——
     * 宿主的排查方向会被引向网络，而真实原因是配置。</p>
     *
     * @param error    原始异常（内部会先 unwrap）
     * @param fallback 无法提取时的兜底消息键
     * @return 消息键
     */
    public static String messageKeyOf(Throwable error, String fallback) {
        Throwable unwrapped = unwrap(error);
        if (unwrapped instanceof IotException iotException
                && iotException.getMessageKey() != null && !iotException.getMessageKey().isBlank()) {
            return iotException.getMessageKey();
        }
        return fallback;
    }

    /**
     * 构造以原始异常完成为失败的 Future。
     *
     * @param throwable 业务异常
     * @param <T>       结果类型
     * @return 失败的 Future
     */
    public static <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(unwrap(throwable));
        return future;
    }

    /**
     * 把任意 Stage 归一化为「交付原始异常」的 Future。
     *
     * <p>成功时原样传递结果，失败时把解包后的业务异常交给调用方。</p>
     *
     * @param stage 源 Stage
     * @param <T>   结果类型
     * @return 归一化后的 Future
     */
    public static <T> CompletableFuture<T> normalize(CompletionStage<T> stage) {
        CompletableFuture<T> result = new CompletableFuture<>();
        stage.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            result.complete(value);
        });
        return result;
    }
}
