/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.undertow.rest;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.undertow.BaseUndertowTest;
import org.junit.jupiter.api.Test;

public class RestUndertowProducerPutTest extends BaseUndertowTest {

    @Test
    public void testUndertowProducerPut() throws Exception {
        getMockEndpoint("mock:input").expectedMessageCount(1);

        fluentTemplate.withBody("Donald Duck").withHeader("id", "123").to("direct:start").send();

        assertMockEndpointsSatisfied();
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                // configure to use localhost with the given port
                restConfiguration().component("undertow").host("localhost").port(getPort());

                from("direct:start")
                        .to("rest:put:users/{id}");

                // use the rest DSL to define the rest services
                rest("/users/")
                        .put("{id}")
                        .to("mock:input");
            }
        };
    }

}
