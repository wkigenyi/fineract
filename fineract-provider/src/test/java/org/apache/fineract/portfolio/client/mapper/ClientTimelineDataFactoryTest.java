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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.apache.fineract.portfolio.client.data.ClientTimelineData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class ClientTimelineDataFactoryTest {

    @Test
    void populatesSubmittedByFromAuditUser() {
        final LocalDate submittedOn = LocalDate.of(2026, 8, 12);
        final LocalDate activatedOn = LocalDate.of(2026, 8, 12);

        final AppUser submittedBy = mock(AppUser.class);
        when(submittedBy.getUsername()).thenReturn("mifos");
        when(submittedBy.getFirstname()).thenReturn("App");
        when(submittedBy.getLastname()).thenReturn("Administrator");

        final AppUser activatedBy = mock(AppUser.class);
        when(activatedBy.getUsername()).thenReturn("mifos");
        when(activatedBy.getFirstname()).thenReturn("App");
        when(activatedBy.getLastname()).thenReturn("Administrator");

        final Client client = mock(Client.class);
        when(client.getSubmittedOnDate()).thenReturn(submittedOn);
        when(client.getActivationDate()).thenReturn(activatedOn);
        when(client.getActivatedBy()).thenReturn(activatedBy);
        when(client.isClosed()).thenReturn(false);

        final ClientTimelineData timeline = ClientTimelineDataFactory.from(client, submittedBy);

        assertEquals(submittedOn, timeline.getSubmittedOnDate());
        assertEquals("mifos", timeline.getSubmittedByUsername());
        assertEquals("App", timeline.getSubmittedByFirstname());
        assertEquals("Administrator", timeline.getSubmittedByLastname());
        assertEquals(activatedOn, timeline.getActivatedOnDate());
        assertEquals("App", timeline.getActivatedByFirstname());
        assertEquals("Administrator", timeline.getActivatedByLastname());
        assertNull(timeline.getClosedOnDate());
        assertNull(timeline.getClosedByUsername());
    }

    @Test
    void leavesSubmittedByEmptyWhenAuditUserMissing() {
        final Client client = mock(Client.class);
        when(client.getSubmittedOnDate()).thenReturn(LocalDate.of(2026, 8, 12));
        when(client.isClosed()).thenReturn(false);

        final ClientTimelineData timeline = ClientTimelineDataFactory.from(client, null);

        assertNull(timeline.getSubmittedByUsername());
        assertNull(timeline.getSubmittedByFirstname());
        assertNull(timeline.getSubmittedByLastname());
    }
}
