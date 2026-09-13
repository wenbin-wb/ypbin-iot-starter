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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.exception.ConnectionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Stages} 测试：确保 SPI 交付的是原始领域异常而非 CompletionException 包装。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class StagesTest {

    @Test
    @DisplayName("STAGES-01 unwrap 必须逐层解包到业务异常")
    void unwrapMustPeelWrappers() {
        ConnectionException business = new ConnectionException("c1", "iot.test");
        assertThat(Stages.unwrap(business)).isSameAs(business);
        assertThat(Stages.unwrap(new CompletionException(business))).isSameAs(business);
        assertThat(Stages.unwrap(new ExecutionException(new CompletionException(business))))
                .isSameAs(business);
    }

    @Test
    @DisplayName("STAGES-02 unwrap 对普通异常原样返回")
    void unwrapMustReturnPlainException() {
        IllegalStateException plain = new IllegalStateException("plain");
        assertThat(Stages.unwrap(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("STAGES-03 failed() 交付的必须是原始异常")
    void failedMustDeliverRawException() {
        ConnectionException business = new ConnectionException("c1", "iot.test");
        Throwable actual = Stages.failed(business).handle((value, error) -> error).join();
        assertThat(actual).isSameAs(business);
    }

    @Test
    @DisplayName("STAGES-04 normalize() 必须去掉 CompletionException 包装")
    void normalizeMustStripWrapper() {
        ConnectionException business = new ConnectionException("c1", "iot.test");
        CompletableFuture<String> source = new CompletableFuture<>();
        source.completeExceptionally(business);
        // 经 thenApply 后会被包成 CompletionException
        Throwable wrapped = source.thenApply(value -> value).handle((value, error) -> error).join();
        assertThat(wrapped).isInstanceOf(CompletionException.class);

        Throwable normalized = Stages.normalize(source.thenApply(value -> value))
                .handle((value, error) -> error).join();
        assertThat(normalized).isSameAs(business);
    }

    @Test
    @DisplayName("STAGES-05 normalize() 成功路径必须透传结果")
    void normalizeMustPassThroughValue() {
        CompletableFuture<String> source = CompletableFuture.completedFuture("ok");
        assertThat(Stages.normalize(source).join()).isEqualTo("ok");
    }
}
