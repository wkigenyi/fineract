/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.salaam.fineract.murabaha.api;

import com.salaam.fineract.murabaha.data.MurabahaDealData;
import com.salaam.fineract.murabaha.data.MurabahaProductData;
import com.salaam.fineract.murabaha.data.MurabahaProductRequest;
import com.salaam.fineract.murabaha.data.MurabahaPurchaseRequest;
import com.salaam.fineract.murabaha.data.MurabahaRebateRequest;
import com.salaam.fineract.murabaha.data.MurabahaSaleRequest;
import com.salaam.fineract.murabaha.service.MurabahaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Path("/v1/murabaha")
@Produces({ MediaType.APPLICATION_JSON })
@Consumes({ MediaType.APPLICATION_JSON })
@Component
@Tag(name = "Murabaha", description = "Salaam Bank Murabaha deals: the bank buys the asset, then sells it to the customer at cost plus margin")
@RequiredArgsConstructor
public class MurabahaApiResource {

    private final MurabahaService murabahaService;

    @POST
    @Path("products")
    @Operation(summary = "Mark a loan product as Murabaha and set its purchase GL accounts")
    public MurabahaProductData registerProduct(final MurabahaProductRequest request) {
        return murabahaService.registerProduct(request);
    }

    @GET
    @Path("products")
    @Operation(summary = "List Murabaha loan products")
    public List<MurabahaProductData> retrieveProducts() {
        return murabahaService.retrieveProducts();
    }

    @GET
    @Path("deals/{loanId}")
    @Operation(summary = "Retrieve the Murabaha deal for a loan")
    public MurabahaDealData retrieveDeal(@PathParam("loanId") final Long loanId) {
        return murabahaService.retrieveDeal(loanId);
    }

    @POST
    @Path("deals/{loanId}/purchase")
    @Operation(summary = "Record the bank's purchase of the asset and post it to the clearing account")
    public MurabahaDealData recordPurchase(@PathParam("loanId") final Long loanId, final MurabahaPurchaseRequest request) {
        return murabahaService.recordPurchase(loanId, request);
    }

    @POST
    @Path("deals/{loanId}/sale")
    @Operation(summary = "Sell the asset to the customer: disburse the cost and add the margin as deferred profit")
    public MurabahaDealData recordSale(@PathParam("loanId") final Long loanId, final MurabahaSaleRequest request) {
        return murabahaService.recordSale(loanId, request);
    }

    @POST
    @Path("deals/{loanId}/rebate")
    @Operation(summary = "Give an early settlement rebate (ibra) on the unearned margin")
    public MurabahaDealData applyRebate(@PathParam("loanId") final Long loanId, final MurabahaRebateRequest request) {
        return murabahaService.applyRebate(loanId, request);
    }
}
