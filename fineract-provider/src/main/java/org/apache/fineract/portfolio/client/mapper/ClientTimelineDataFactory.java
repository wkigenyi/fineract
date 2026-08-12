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
package org.apache.fineract.portfolio.client.mapper;

import org.apache.fineract.portfolio.client.data.ClientTimelineData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.useradministration.domain.AppUser;

/**
 * Builds {@link ClientTimelineData} for client detail. Submitted-by comes from the audit user
 * ({@code m_client.created_by}); Fineract dropped {@code submittedon_userid}.
 */
final class ClientTimelineDataFactory {

    private ClientTimelineDataFactory() {}

    static ClientTimelineData from(final Client client, final AppUser submittedBy) {
        final AppUser activatedBy = client.getActivatedBy();
        final boolean closed = client.isClosed();
        final AppUser closedBy = closed ? client.getClosedBy() : null;

        return new ClientTimelineData(client.getSubmittedOnDate(), username(submittedBy), firstname(submittedBy), lastname(submittedBy),
                client.getActivationDate(), username(activatedBy), firstname(activatedBy), lastname(activatedBy),
                closed ? client.getClosureDate() : null, username(closedBy), firstname(closedBy), lastname(closedBy));
    }

    private static String username(final AppUser user) {
        return user == null ? null : user.getUsername();
    }

    private static String firstname(final AppUser user) {
        return user == null ? null : user.getFirstname();
    }

    private static String lastname(final AppUser user) {
        return user == null ? null : user.getLastname();
    }
}
