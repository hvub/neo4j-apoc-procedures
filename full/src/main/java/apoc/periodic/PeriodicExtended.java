/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.periodic;

import static org.neo4j.graphdb.QueryExecutionType.QueryType.READ_ONLY;
import static org.neo4j.graphdb.QueryExecutionType.QueryType.READ_WRITE;
import static org.neo4j.graphdb.QueryExecutionType.QueryType.SCHEMA_WRITE;
import static org.neo4j.graphdb.QueryExecutionType.QueryType.WRITE;

import apoc.Extended;
import apoc.Pools;
import apoc.util.Util;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

@Extended
public class PeriodicExtended {
    @Context
    public GraphDatabaseService db;

    @Context
    public TerminationGuard terminationGuard;

    @Context
    public Log log;

    @Context
    public Pools pools;

    @Context
    public Transaction tx;

    private void recordError(Map<String, Long> executionErrors, Exception e) {
        String msg = ExceptionUtils.getRootCause(e).getMessage();
        // String msg =
        // ExceptionUtils.getThrowableList(e).stream().map(Throwable::getMessage).collect(Collectors.joining(","))
        executionErrors.compute(msg, (s, i) -> i == null ? 1 : i + 1);
    }

    @Procedure(mode = Mode.SCHEMA)
    @Description(
            "apoc.periodic.submitSchema(name, statement, $config) - equivalent to apoc.periodic.submit which can also accept schema operations")
    public Stream<Periodic.JobInfo> submitSchema(
            @Name("name") String name,
            @Name("statement") String statement,
            @Name(value = "params", defaultValue = "{}") Map<String, Object> config) {
        validateQuery(statement);
        return PeriodicUtils.submitProc(name, statement, config, db, log, pools);
    }

    private void validateQuery(String statement) {
        Util.validateQuery(db, statement, READ_ONLY, WRITE, READ_WRITE, SCHEMA_WRITE);
    }

    /**
     * as long as cypherLoop does not return 0, null, false, or the empty string as 'value' do:
     *
     * invoke cypherAction in batched transactions being feeded from cypherIteration running in main thread
     *
     * @param cypherLoop
     * @param cypherIterate
     * @param cypherAction
     * @param batchSize
     */
    @Procedure(mode = Mode.WRITE)
    @Deprecated
    @Description(
            "apoc.periodic.rock_n_roll_while('some cypher for knowing when to stop', 'some cypher for iteration', 'some cypher as action on each iteration', 10000) YIELD batches, total - run the action statement in batches over the iterator statement's results in a separate thread. Returns number of batches and total processed rows")
    public Stream<LoopingBatchAndTotalResult> rock_n_roll_while(
            @Name("cypherLoop") String cypherLoop,
            @Name("cypherIterate") String cypherIterate,
            @Name("cypherAction") String cypherAction,
            @Name("batchSize") long batchSize) {
        Map<String, String> fieldStatement = Util.map(
                "cypherLoop", cypherLoop,
                "cypherIterate", cypherIterate);
        validateQueries(fieldStatement);
        Stream<LoopingBatchAndTotalResult> allResults = Stream.empty();

        Map<String, Object> loopParams = new HashMap<>(1);
        Object value = null;

        while (true) {
            loopParams.put("previous", value);

            try (Result result = tx.execute(cypherLoop, loopParams)) {
                value = result.next().get("loop");
                if (!Util.toBoolean(value)) return allResults;
            }

            String periodicId = UUID.randomUUID().toString();
            log.info(
                    "starting batched operation using iteration `%s` in separate thread with id: `%s`",
                    cypherIterate, periodicId);

            try (Result result = tx.execute(cypherIterate)) {
                Stream<BatchAndTotalResult> oneResult = PeriodicUtils.iterateAndExecuteBatchedInSeparateThread(
                        db,
                        terminationGuard,
                        log,
                        pools,
                        (int) batchSize,
                        false,
                        false,
                        0,
                        result,
                        (tx, params) -> tx.execute(cypherAction, params).getQueryStatistics(),
                        50,
                        -1,
                        periodicId);
                final Object loopParam = value;
                allResults = Stream.concat(allResults, oneResult.map(r -> r.inLoop(loopParam)));
            }
        }
    }

    private void validateQueries(Map<String, String> fieldStatement) {
        String error = fieldStatement.entrySet().stream()
                .map(e -> {
                    try {
                        validateQuery(e.getValue());
                        return null;
                    } catch (Exception exception) {
                        return String.format(
                                "Exception for field `%s`, message: %s", e.getKey(), exception.getMessage());
                    }
                })
                .filter(e -> e != null)
                .collect(Collectors.joining("\n"));
        if (!error.isEmpty()) {
            throw new RuntimeException(error);
        }
    }

    @Deprecated
    @Procedure(mode = Mode.WRITE)
    @Description(
            "apoc.periodic.rock_n_roll('some cypher for iteration', 'some cypher as action on each iteration', 10000) YIELD batches, total - run the action statement in batches over the iterator statement's results in a separate thread. Returns number of batches and total processed rows")
    public Stream<BatchAndTotalResult> rock_n_roll(
            @Name("cypherIterate") String cypherIterate,
            @Name("cypherAction") String cypherAction,
            @Name("batchSize") long batchSize) {
        Map<String, String> fieldStatement = Util.map(
                "cypherIterate", cypherIterate,
                "cypherAction", cypherAction);
        validateQueries(fieldStatement);

        String periodicId = UUID.randomUUID().toString();
        log.info(
                "starting batched operation using iteration `%s` in separate thread with id: `%s`",
                cypherIterate, periodicId);
        try (Result result = tx.execute(cypherIterate)) {
            return PeriodicUtils.iterateAndExecuteBatchedInSeparateThread(
                    db,
                    terminationGuard,
                    log,
                    pools,
                    (int) batchSize,
                    false,
                    false,
                    0,
                    result,
                    (tx, p) -> tx.execute(cypherAction, p).getQueryStatistics(),
                    50,
                    -1,
                    periodicId);
        }
    }

    private long executeAndReportErrors(
            Transaction tx,
            BiConsumer<Transaction, Map<String, Object>> consumer,
            Map<String, Object> params,
            List<Map<String, Object>> batch,
            int returnValue,
            AtomicLong localCount,
            BatchAndTotalCollector collector) {
        try {
            consumer.accept(tx, params);
            if (localCount != null) {
                localCount.getAndIncrement();
            }
            return returnValue;
        } catch (Exception e) {
            collector.incrementFailedOps(batch.size());
            collector.amendFailedParamsMap(batch);
            recordError(collector.getOperationErrors(), e);
            throw e;
        }
    }
}
