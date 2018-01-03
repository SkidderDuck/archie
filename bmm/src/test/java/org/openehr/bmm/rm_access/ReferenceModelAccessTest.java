package org.openehr.bmm.rm_access;

/*
 * #%L
 * OpenEHR - Java Model Stack
 * %%
 * Copyright (C) 2016 - 2017  Cognitive Medical Systems, Inc (http://www.cognitivemedicine.com).
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 * Author: Claude Nanjo
 */

import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Test;
import org.openehr.bmm.core.BmmModel;
import org.openehr.bmm.rmaccess.ReferenceModelAccess;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ReferenceModelAccessTest {

    private List<String> schemaDirectories;

    @Before
    public void setup() {
        schemaDirectories = new ArrayList<>();
        String path = ReferenceModelAccessTest.class.getResource("/cimi/CIMI-RM-3.0.5.bmm").getFile();
        path = path.substring(0, path.lastIndexOf('/'));
        schemaDirectories.add(path);
    }

    @Test
    public void getValidModels() throws Exception {
        ReferenceModelAccess access = new ReferenceModelAccess();
        access.initializeAll(schemaDirectories);
        Map<String, BmmModel> models = access.getValidModels();
        assertFalse(access.getValidator().hasErrors());
        //if we don't set the top level schema, it has warnings, apparently
        assertTrue(access.getValidator().hasWarnings());
        assertEquals(2, models.size());
        assertEquals(Sets.newHashSet("cimi_rm_3.0.5", "cimi_rm_clinical_0.0.2"), models.keySet());
    }

}