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

package harry.generators;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import harry.core.Configuration;
import harry.core.Run;
import harry.ddl.ColumnSpec;
import harry.ddl.SchemaGenerators;
import harry.ddl.SchemaSpec;
import harry.generators.distribution.Distribution;
import harry.model.NoOpChecker;
import harry.model.OpSelectors;
import harry.model.sut.SystemUnderTest;
import harry.runner.MutatingPartitionVisitor;
import harry.runner.MutatingRowVisitor;
import harry.runner.PartitionVisitor;
import harry.runner.SinglePartitionValidator;
import harry.util.TestRunner;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.distributed.impl.RowUtil;
import relocated.shaded.com.google.common.collect.Iterators;

public class DataGeneratorsIntegrationTest extends CQLTester
{
    @Test
    public void testTimestampTieResolution() throws Throwable
    {
        Random rng = new Random(1);
        for (ColumnSpec.DataType<?> dataType : new ColumnSpec.DataType[]{ ColumnSpec.int8Type,
                                                                       ColumnSpec.int16Type,
                                                                       ColumnSpec.int32Type,
                                                                       ColumnSpec.int64Type,
                                                                       ColumnSpec.asciiType,
                                                                       ColumnSpec.floatType,
                                                                       ColumnSpec.doubleType })
        {
            createTable(String.format("CREATE TABLE %%s (pk int PRIMARY KEY, v %s)",
                                      dataType.toString()));
            for (int i = 0; i < 10_000; i++)
            {
                long d1 = dataType.generator().adjustEntropyDomain(rng.nextLong());
                long d2 = dataType.generator().adjustEntropyDomain(rng.nextLong());
                for (long d : new long[]{ d1, d2 })
                {
                    execute("INSERT INTO %s (pk, v) VALUES (?,?) USING TIMESTAMP 1",
                            i, dataType.generator().inflate(d));
                }

                if (dataType.compareLexicographically(d1, d2) > 0)
                    assertRows(execute("SELECT v FROM %s WHERE pk=?", i),
                               row(dataType.generator().inflate(d1)));
                else
                    assertRows(execute("SELECT v FROM %s WHERE pk=?", i),
                               row(dataType.generator().inflate(d2)));
            }
        }
    }

    @Test
    public void queryParseabilityTest() throws Throwable
    {
        Generator<SchemaSpec> gen = new SchemaGenerators.Builder(KEYSPACE).partitionKeyColumnCount(2, 4)
                                                                          .clusteringColumnCount(1, 4)
                                                                          .regularColumnCount(1, 4)
                                                                          .staticColumnCount(1, 4)
                                                                          .generator();

        TestRunner.test(gen,
                        (schema) -> {
                            createTable(schema.compile().cql());

                            Configuration.ConfigurationBuilder builder = Configuration.fromFile(getClass().getClassLoader().getResource("single_partition_test.yml").getFile())
                                         .unbuild()
                                         .setSchemaProvider(new Configuration.FixedSchemaProviderConfiguration(schema, null, null, null, null))
                                         .setSUT(CqlTesterSut::new);

                            for (OpSelectors.OperationKind opKind : OpSelectors.OperationKind.values())
                            {
                                Run run = builder
                                          .setClusteringDescriptorSelector((rng, schema_) -> {
                                              return new OpSelectors.DefaultDescriptorSelector(rng,
                                                                                               OpSelectors.columnSelectorBuilder().forAll(schema_).build(),
                                                                                               OpSelectors.OperationSelector.weighted(Surjections.weights(100), opKind),
                                                                                               new Distribution.ConstantDistribution(2),
                                                                                               new Distribution.ConstantDistribution(2),
                                                                                               100);
                                          })
                                          .build()
                                          .createRun();

                                PartitionVisitor visitor = new MutatingPartitionVisitor(run, MutatingRowVisitor::new);
                                for (int lts = 0; lts < 100; lts++)
                                    visitor.visitPartition(lts);
                            }

                            Run run = builder.build()
                                             .createRun();
                            PartitionVisitor visitor = new SinglePartitionValidator(100, run, NoOpChecker::new);
                            for (int lts = 0; lts < 100; lts++)
                                visitor.visitPartition(lts);

                        });

    }

    public class CqlTesterSut implements SystemUnderTest
    {
        public boolean isShutdown()
        {
            return false;
        }

        public void shutdown()
        {
            cleanup();
        }

        public void schemaChange(String statement)
        {
            createTable(statement);
        }

        public Object[][] execute(String statement, ConsistencyLevel cl, Object... bindings)
        {
            try
            {
                UntypedResultSet res = DataGeneratorsIntegrationTest.this.execute(statement, bindings);
                if (res == null)
                    return new Object[][] {};

                return Iterators.toArray(RowUtil.toIter(res), Object[].class);
            }
            catch (Throwable throwable)
            {
                throw new RuntimeException(throwable);
            }
        }

        public CompletableFuture<Object[][]> executeAsync(String statement, ConsistencyLevel cl, Object... bindings)
        {
            return CompletableFuture.completedFuture(execute(statement, cl, bindings));
        }
    }
}

