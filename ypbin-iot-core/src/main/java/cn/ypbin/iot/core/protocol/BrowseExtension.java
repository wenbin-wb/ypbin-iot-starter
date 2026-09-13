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
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.model.BrowseNode;
import cn.ypbin.iot.core.model.BrowseRequest;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 节点目录浏览扩展（OPC UA、BACnet、KNX 等）。
 *
 * <p>实现本接口的适配器必须在
 * {@link ProtocolDescriptor#extensions()} 中声明，且
 * {@link ProtocolDescriptor#capabilities()} 必须包含
 * {@link ProtocolCapability#BROWSE}，否则注册时校验失败。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface BrowseExtension extends ProtocolExtension {

    /**
     * 浏览指定节点下的子节点。
     *
     * @param request 浏览请求
     * @return 子节点列表 Stage
     */
    CompletionStage<List<BrowseNode>> browse(BrowseRequest request);
}
