/*
 * Copyright (c) 2013-2015 Cinchapi, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cinchapi.concourse.server.storage;

import java.util.Map;
import java.util.Set;

import org.cinchapi.concourse.server.concurrent.LockService;
import org.cinchapi.concourse.server.concurrent.RangeLockService;
import org.cinchapi.concourse.server.storage.temp.Limbo;
import org.cinchapi.concourse.server.storage.temp.Write;
import org.cinchapi.concourse.thrift.Operator;
import org.cinchapi.concourse.thrift.TObject;
import org.cinchapi.concourse.time.Time;
import org.cinchapi.concourse.util.DataServices;

import com.google.common.collect.Sets;

/**
 * A {@link BufferedStore} holds data in {@link Limbo} buffer before
 * making batch commits to some other {@link PermanentStore}.
 * <p>
 * Data is written to the buffer until the buffer is full, at which point the
 * BufferingStore will flush the data to the destination store. Reads are
 * handled by taking the XOR (see {@link Sets#symmetricDifference(Set, Set)} or
 * XOR truth (see <a
 * href="http://en.wikipedia.org/wiki/Exclusive_or#Truth_table"
 * >http://en.wikipedia.org/wiki/Exclusive_or#Truth_table</a>) of the values
 * read from the buffer and the destination.
 * </p>
 * 
 * @author Jeff Nelson
 */
public abstract class BufferedStore extends BaseStore {

    // NOTE ON LOCKING:
    // ================
    // We can't do any global locking to coordinate the #buffer and #destination
    // in this class because we cannot make assumptions about how the
    // implementing class actually wants to handle concurrency (i.e. an
    // AtomicOperation does not grab any coordinating locks until it goes to
    // commit so that it doesn't do any unnecessary blocking).

    // NOTE ON HISTORICAL READS
    // ========================
    // Buffered historical reads do not merely delegate to their timestamp
    // accepting counterparts because we expect the #destination to use
    // different code paths for present vs historical reads unlike Limbo.

    /**
     * The {@code buffer} is the place where data is initially stored. The
     * contained data is eventually moved to the {@link #destination} when the
     * {@link Limbo#transport(PermanentStore)} method is called.
     */
    protected final Limbo buffer;

    /**
     * The {@code destination} is the place where data is stored when it is
     * transferred from the {@link #buffer}. The {@code destination} defines its
     * protocol for accepting data in the {@link PermanentStore#accept(Write)}
     * method.
     */
    protected final PermanentStore destination;

    /**
     * The {@link LockService} that is used to coordinate concurrent operations.
     */
    protected LockService lockService;

    /**
     * The {@link RangeLockService} that is used to coordinate concurrent
     * operations.
     */
    protected RangeLockService rangeLockService;

    /**
     * Construct a new instance.
     * 
     * @param transportable
     * @param destination
     * @param lockService
     * @param rangeLockService
     */
    protected BufferedStore(Limbo transportable, PermanentStore destination,
            LockService lockService, RangeLockService rangeLockService) {
        this.buffer = transportable;
        this.destination = destination;
        this.lockService = lockService;
        this.rangeLockService = rangeLockService;
    }

    /**
     * Add {@code key} as {@code value} to {@code record}.
     * <p>
     * This method maps {@code key} to {@code value} in {@code record}, if and
     * only if that mapping does not <em>currently</em> exist (i.e.
     * {@link #verify(String, Object, long)} is {@code false}). Adding
     * {@code value} to {@code key} does not replace any existing mappings from
     * {@code key} in {@code record} because a field may contain multiple
     * distinct values.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @return {@code true} if the mapping is added
     */
    public boolean add(String key, TObject value, long record) {
        return add(key, value, record, true, true, true);
    }

    @Override
    public Map<Long, String> audit(long record) {
        return audit(record, false);
    }

    @Override
    public Map<Long, String> audit(String key, long record) {
        return audit(key, record, false);
    }

    @Override
    public Map<TObject, Set<Long>> browse(String key) {
        return browse(key, false);
    }

    @Override
    public Map<TObject, Set<Long>> browse(String key, long timestamp) {
        Map<TObject, Set<Long>> context = destination.browse(key, timestamp);
        return buffer.browse(key, timestamp, context);
    }

    /**
     * Remove {@code key} as {@code value} from {@code record}.
     * <p>
     * This method deletes the mapping from {@code key} to {@code value} in
     * {@code record}, if that mapping <em>currently</em> exists (i.e.
     * {@link #verify(String, Object, long)} is {@code true}. No other mappings
     * from {@code key} in {@code record} are affected.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @return {@code true} if the mapping is removed
     */
    public boolean remove(String key, TObject value, long record) {
        return remove(key, value, record, true, true, true);
    }

    @Override
    public Set<Long> search(String key, String query) {
        // FIXME: should this be implemented using a context instead?
        return Sets.symmetricDifference(buffer.search(key, query),
                destination.search(key, query));
    }

    @Override
    public Map<String, Set<TObject>> select(long record) {
        return browse(record, false);
    }

    @Override
    public Map<String, Set<TObject>> select(long record, long timestamp) {
        Map<String, Set<TObject>> context = destination.select(record,
                timestamp);
        return buffer.browse(record, timestamp, context);
    }

    @Override
    public Set<TObject> select(String key, long record) {
        return select(key, record, false);
    }

    @Override
    public Set<TObject> select(String key, long record, long timestamp) {
        Set<TObject> context = destination.select(key, record, timestamp);
        return buffer.select(key, record, timestamp, context);
    }

    /**
     * Set {@code key} as {@code value} in {@code record}.
     * <p>
     * This method will remove all the values currently mapped from {@code key}
     * in {@code record} and add {@code value} without performing any validity
     * checks.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     */
    public void set(String key, TObject value, long record) {
        DataServices.sanityCheck(key, value);
        Set<TObject> values = select(key, record);
        for (TObject val : values) {
            buffer.insert(Write.remove(key, val, record)); /* Authorized */
        }
        buffer.insert(Write.add(key, value, record)); /* Authorized */
    }

    @Override
    public boolean verify(String key, TObject value, long record) {
        return verify(key, value, record, false);

    }

    @Override
    public boolean verify(String key, TObject value, long record, long timestamp) {
        return buffer.verify(Write.notStorable(key, value, record), timestamp,
                destination.verify(key, value, record, timestamp));
    }

    /**
     * Add {@code key} as {@code value} to {@code record} with the directive to
     * {@code sync} the data or not. Depending upon the implementation of the
     * {@link #buffer}, a sync may guarantee that the data is durably stored.
     * <p>
     * This method maps {@code key} to {@code value} in {@code record}, if and
     * only if that mapping does not <em>currently</em> exist (i.e.
     * {@link #verify(String, Object, long)} is {@code false}). Adding
     * {@code value} to {@code key} does not replace any existing mappings from
     * {@code key} in {@code record} because a field may contain multiple
     * distinct values.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @param sync
     * @param validate
     * @param lockOnVerify
     * @return {@code true} if the mapping is added
     */
    protected boolean add(String key, TObject value, long record, boolean sync,
            boolean validate, boolean lockOnVerify) {
        DataServices.sanityCheck(key, value);
        Write write = Write.add(key, value, record);
        if(!validate || !verify(write, lockOnVerify)) {
            return buffer.insert(write, sync); /* Authorized */
        }
        return false;
    }

    /**
     * Audit {@code record} either using safe methods or unsafe methods..
     * <p>
     * This method returns a log of revisions in {@code record} as a Map
     * associating timestamps (in milliseconds) to CAL statements:
     * </p>
     * 
     * <pre>
     * {
     *    "13703523370000" : "ADD 'foo' AS 'bar bang' TO 1", 
     *    "13703524350000" : "ADD 'baz' AS '10' TO 1",
     *    "13703526310000" : "REMOVE 'foo' AS 'bar bang' FROM 1"
     * }
     * </pre>
     * 
     * @param record
     * @param boolean
     * @return the the revision log
     */
    protected Map<Long, String> audit(long record, boolean unsafe) {
        Map<Long, String> result;
        if(unsafe && destination instanceof Compoundable) {
            result = ((Compoundable) (destination)).auditUnsafe(record);
        }
        else {
            result = destination.audit(record);
        }
        result.putAll(buffer.audit(record));
        return result;
    }

    /**
     * Audit {@code key} in {@code record} either using safe methods or unsafe
     * methods.
     * <p>
     * This method returns a log of revisions in {@code record} as a Map
     * associating timestamps (in milliseconds) to CAL statements:
     * </p>
     * 
     * <pre>
     * {
     *    "13703523370000" : "ADD 'foo' AS 'bar bang' TO 1", 
     *    "13703524350000" : "ADD 'baz' AS '10' TO 1",
     *    "13703526310000" : "REMOVE 'foo' AS 'bar bang' FROM 1"
     * }
     * </pre>
     * 
     * @param key
     * @param record
     * @param unsafe
     * @return the revision log
     */
    protected Map<Long, String> audit(String key, long record, boolean unsafe) {
        Map<Long, String> result;
        if(unsafe && destination instanceof Compoundable) {
            result = ((Compoundable) (destination)).auditUnsafe(key, record);
        }
        else {
            result = destination.audit(key, record);
        }
        result.putAll(buffer.audit(key, record));
        return result;
    }

    /**
     * Browse {@code record} either using safe or unsafe methods.
     * <p>
     * This method returns a mapping from each of the nonempty keys in
     * {@code record} to a Set of associated values. If there are no such keys,
     * an empty Map is returned.
     * </p>
     * 
     * @param record
     * @param unsafe
     * @return a possibly empty Map of data.
     */
    protected Map<String, Set<TObject>> browse(long record, boolean unsafe) {
        Map<String, Set<TObject>> context;
        if(unsafe && destination instanceof Compoundable) {
            context = ((Compoundable) (destination)).browseUnsafe(record);
        }
        else {
            context = destination.select(record);
        }
        return buffer.browse(record, Time.now(), context);
    }

    /**
     * Browse {@code key} either using safe or unsafe methods.
     * <p>
     * This method returns a mapping from each of the values that is currently
     * indexed to {@code key} to a Set the records that contain {@code key} as
     * the associated value. If there are no such values, an empty Map is
     * returned.
     * </p>
     * 
     * @param key
     * @param unsafe
     * @return a possibly empty Map of data
     */
    protected Map<TObject, Set<Long>> browse(String key, boolean unsafe) {
        Map<TObject, Set<Long>> context;
        if(unsafe && destination instanceof Compoundable) {
            context = ((Compoundable) (destination)).browseUnsafe(key);
        }
        else {
            context = destination.browse(key);
        }
        return buffer.browse(key, Time.now(), context);
    }

    @Override
    protected Map<Long, Set<TObject>> doExplore(long timestamp, String key,
            Operator operator, TObject... values) {
        Map<Long, Set<TObject>> context = destination.explore(timestamp, key,
                operator, values);
        return buffer.explore(context, timestamp, key, operator, values);
    }

    protected Map<Long, Set<TObject>> doExplore(String key, Operator operator,
            TObject... values) {
        return doExplore(key, operator, values, false);
    }

    /**
     * Do the work to explore {@code key} {@code operator} {@code values}
     * without worry about normalizing the {@code operator} or {@code values}
     * either using safe or unsafe methods.
     * 
     * @param key
     * @param operator
     * @param values
     * @return {@code Map}
     */
    protected Map<Long, Set<TObject>> doExplore(String key, Operator operator,
            TObject[] values, boolean unsafe) {
        Map<Long, Set<TObject>> context;
        if(unsafe && destination instanceof Compoundable) {
            context = ((Compoundable) (destination)).doExploreUnsafe(key,
                    operator, values);
        }
        else {
            context = destination.explore(key, operator, values);
        }
        return buffer.explore(context, Time.now(), key, operator, values);
    }

    /**
     * Remove {@code key} as {@code value} from {@code record} with the
     * directive to {@code sync} the data or not. Depending upon the
     * implementation of the {@link #buffer}, a sync may guarantee that the data
     * is durably stored.
     * <p>
     * This method deletes the mapping from {@code key} to {@code value} in
     * {@code record}, if that mapping <em>currently</em> exists (i.e.
     * {@link #verify(String, Object, long)} is {@code true}. No other mappings
     * from {@code key} in {@code record} are affected.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @param sync
     * @param validate
     * @param lockOnVerify
     * @return {@code true} if the mapping is removed
     */
    protected boolean remove(String key, TObject value, long record,
            boolean sync, boolean validate, boolean lockOnVerify) {
        DataServices.sanityCheck(key, value);
        Write write = Write.remove(key, value, record);
        if(!validate || verify(write, lockOnVerify)) {
            return buffer.insert(write, sync); /* Authorized */
        }
        return false;
    }

    /**
     * Select {@code key} from {@code record} either using safe or unsafe
     * methods.
     * <p>
     * This method returns the values currently mapped from {@code key} in
     * {@code record}. The returned Set is nonempty if and only if {@code key}
     * is a member of the Set returned from {@link #describe(long)}.
     * </p>
     * 
     * @param key
     * @param record
     * @param lock
     * @return a possibly empty Set of values
     */
    protected Set<TObject> select(String key, long record, boolean lock) {
        Set<TObject> context;
        if(!lock && destination instanceof Compoundable) {
            context = ((Compoundable) (destination)).selectUnsafe(key, record);
        }
        else {
            context = destination.select(key, record);
        }
        return buffer.select(key, record, Time.now(), context);
    }

    /**
     * Set {@code key} as {@code value} in {@code record}.
     * <p>
     * This method will remove all the values currently mapped from {@code key}
     * in {@code record} and add {@code value} without performing any validity
     * checks.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @param lockOnRead
     */
    protected void set(String key, TObject value, long record,
            boolean lockOnRead) {
        DataServices.sanityCheck(key, value);
        Set<TObject> values = select(key, record, lockOnRead);
        for (TObject val : values) {
            buffer.insert(Write.remove(key, val, record)); /* Authorized */
        }
        buffer.insert(Write.add(key, value, record)); /* Authorized */
    }

    /**
     * Verify {@code key} equals {@code value} in {@code record} either using
     * safe or unsafe method.
     * <p>
     * This method checks that there is <em>currently</em> a mapping from
     * {@code key} to {@code value} in {@code record}. This method has the same
     * affect as calling {@link #select(String, long)}
     * {@link Set#contains(Object)}.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @return {@code true} if there is a an association from {@code key} to
     *         {@code value} in {@code record}
     */
    protected boolean verify(String key, TObject value, long record,
            boolean unsafe) {
        boolean destResult;
        if(unsafe && destination instanceof Compoundable) {
            destResult = ((Compoundable) (destination)).verifyUnsafe(key,
                    value, record);
        }
        else {
            destResult = destination.verify(key, value, record);
        }
        return buffer.verify(Write.notStorable(key, value, record), destResult);
    }

    /**
     * Shortcut method to verify {@code write}. This method is called from
     * {@link #add(String, TObject, long)} and
     * {@link #remove(String, TObject, long)} so that we can avoid creating a
     * duplicate Write.
     * 
     * @param write
     * @return {@code true} if {@code write} currently exists
     */
    protected final boolean verify(Write write) {
        return verify(write, true);
    }

    /**
     * Shortcut method to verify {@code write}. This method is called from
     * {@link #add(String, TObject, long)} and
     * {@link #remove(String, TObject, long)} so that we can avoid creating a
     * duplicate Write.
     * 
     * @param write
     * @param lock
     * @return {@code true} if {@code write} currently exists
     */
    protected boolean verify(Write write, boolean lock) {
        String key = write.getKey().toString();
        TObject value = write.getValue().getTObject();
        long record = write.getRecord().longValue();
        boolean fromDest = (!lock && destination instanceof Compoundable) ? ((Compoundable) destination)
                .verifyUnsafe(key, value, record) : destination.verify(key,
                value, record);
        return buffer.verify(write, fromDest);
    }

}
