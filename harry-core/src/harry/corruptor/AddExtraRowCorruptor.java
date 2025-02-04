/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package harry.corruptor;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import harry.data.ResultSetRow;
import harry.ddl.SchemaSpec;
import harry.model.OpSelectors;
import harry.model.SelectHelper;
import harry.model.sut.SystemUnderTest;
import harry.operations.WriteHelper;
import harry.operations.Query;

public class AddExtraRowCorruptor implements QueryResponseCorruptor
{
    private static final Logger logger = LoggerFactory.getLogger(AddExtraRowCorruptor.class);

    private final SchemaSpec schema;
    private final OpSelectors.MonotonicClock clock;
    private final OpSelectors.DescriptorSelector descriptorSelector;

    public AddExtraRowCorruptor(SchemaSpec schema,
                                OpSelectors.MonotonicClock clock,
                                OpSelectors.DescriptorSelector descriptorSelector)
    {
        this.schema = schema;
        this.clock = clock;
        this.descriptorSelector = descriptorSelector;
    }

    public boolean maybeCorrupt(Query query, SystemUnderTest sut)
    {
        Set<Long> cds = new HashSet<>();
        long maxLts = 0;
        for (Object[] obj : sut.execute(query.toSelectStatement(), SystemUnderTest.ConsistencyLevel.ALL))
        {
            ResultSetRow row = SelectHelper.resultSetToRow(schema, clock, obj);
            // TODO: extract CD cheaper
            cds.add(row.cd);
            for (int i = 0; i < row.lts.length; i++)
                maxLts = Math.max(maxLts, row.lts[i]);
        }

        boolean partitionIsFull = cds.size() >= descriptorSelector.maxPartitionSize();

        long attempt = 0;
        long cd = descriptorSelector.randomCd(query.pd, attempt, schema);;
        while (!query.match(cd) || cds.contains(cd))
        {
            if (partitionIsFull)
                // We can't pick from the existing CDs, so let's try to come up with a new one that would match the query
                cd += descriptorSelector.randomCd(query.pd, attempt, schema);
            else
                cd = descriptorSelector.randomCd(query.pd, attempt, schema);
            if (attempt++ == 1000)
                return false;
        }

        long[] vds = descriptorSelector.vds(query.pd, cd, maxLts, 0, OpSelectors.OperationKind.INSERT, schema);

        // We do not know if the row was deleted. We could try inferring it, but that
        // still won't help since we can't use it anyways, since collisions between a
        // written value and tombstone are resolved in favour of tombstone, so we're
        // just going to take the next lts.
        logger.info("Corrupting the resultset by writing a row with cd {}", cd);
        sut.execute(WriteHelper.inflateInsert(schema, query.pd, cd, vds, null, clock.rts(maxLts) + 1), SystemUnderTest.ConsistencyLevel.ALL);
        return true;
    }
}