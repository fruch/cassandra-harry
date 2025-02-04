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

package harry.visitors;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import harry.core.Run;
import harry.model.OpSelectors;
import harry.operations.CompiledStatement;

public class LoggingVisitor extends GeneratingVisitor
{
    public LoggingVisitor(Run run,
                          OperationExecutor.RowVisitorFactory rowVisitorFactory)
    {
        super(run, new LoggingVisitorExecutor(run, rowVisitorFactory.make(run)));
    }

    public static class LoggingVisitorExecutor extends MutatingVisitor.MutatingVisitExecutor
    {
        private final BufferedWriter operationLog;

        public LoggingVisitorExecutor(Run run, OperationExecutor rowVisitor)
        {
            super(run, rowVisitor);

            File f = new File("operation.log");
            try
            {
                operationLog = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
            }
            catch (FileNotFoundException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void afterLts(long lts, long pd)
        {
            super.afterLts(lts, pd);
            log("LTS: %d. Pd %d. Finished\n", lts, pd);
        }

        @Override
        protected CompiledStatement operationInternal(long lts, long pd, long cd, long m, long opId, OpSelectors.OperationKind opType)
        {
            CompiledStatement statement = super.operationInternal(lts, pd, cd, m, opId, opType);

            log(String.format("LTS: %d. Pd %d. Cd %d. M %d. OpId: %d Statement %s\n",
                              lts, pd, cd, m, opId, statement));

            return statement;
        }

        private void log(String format, Object... objects)
        {
            try
            {
                operationLog.write(String.format(format, objects));
                operationLog.flush();
            }
            catch (IOException e)
            {
                // ignore
            }
        }
    }
}
