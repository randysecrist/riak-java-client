/*
 * This file is provided to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.basho.riak.client.itest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;

import com.basho.riak.newapi.RiakClient;
import com.basho.riak.newapi.RiakException;
import com.basho.riak.newapi.RiakObject;
import com.basho.riak.newapi.bucket.Bucket;
import com.basho.riak.newapi.cap.UnresolvedConflictException;
import com.basho.riak.newapi.convert.NoKeySpecifedException;
import com.megacorp.commerce.LegacyCart;
import com.megacorp.commerce.ShoppingCart;

/**
 * @author russell
 * 
 */
public abstract class ITestBucket {

    protected RiakClient client;

    @Before public void setUp() throws RiakException {
        client = getClient();
    }

    protected abstract RiakClient getClient() throws RiakException;

    @Test public void basicStore() throws Exception {
        final String bucketName = UUID.randomUUID().toString();

        Bucket b = client.fetchBucket(bucketName).execute();
        RiakObject o = b.store("k", "v").execute();
        assertNull(o);

        RiakObject fetched = b.fetch("k").execute();
        assertEquals("v", fetched.getValue());

        // now update that riak object
        b.store("k", "my new value").execute();
        fetched = b.fetch("k").execute();
        assertEquals("my new value", fetched.getValue());

        b.delete("k").execute();

        // give it time...
        Thread.sleep(500);

        fetched = b.fetch("k").execute();
        assertNull(fetched);
    }

    @Test public void byDefaultSiblingsThrowUnresolvedExceptionOnStore() throws Exception {
        final String bucketName = UUID.randomUUID().toString();

        final Bucket b = client.createBucket(bucketName).allowSiblings(true).execute();
        b.store("k", "v").execute();

        final int numThreads = 2;
        final Collection<Callable<Boolean>> storers = new ArrayList<Callable<Boolean>>(numThreads);

        final ExecutorService es = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final RiakClient c = getClient();
            c.generateAndSetClientId();
            final Bucket bucket = c.fetchBucket(bucketName).execute();

            storers.add(new Callable<Boolean>() {
                public Boolean call() throws RiakException {
                    try {
                        for (int i = 0; i < 5; i++) {
                            bucket.store("k", Thread.currentThread().getName() + "v" + i).execute();
                            Thread.sleep(50);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }
            });
        }

        Collection<Future<Boolean>> results = es.invokeAll(storers);

        for (Future<Boolean> f : results) {
            try {
                f.get();
                fail("Expected siblings");
            } catch (ExecutionException e) {
                assertEquals(UnresolvedConflictException.class, e.getCause().getClass());
            }
        }

        // TODO clean up your mess (teardown)
    }

    /**
     * @see ITestDomainBucket
     * @throws Exception
     */
    @Test public void storeDomainObjectWithKeyAnnotation() throws Exception {
        final String bucketName = UUID.randomUUID().toString() + "_carts";
        final String userId = UUID.randomUUID().toString();

        final Bucket carts = client.createBucket(bucketName).allowSiblings(true).execute();

        final ShoppingCart cart = new ShoppingCart(userId);

        cart.addItem("coffee");
        cart.addItem("fixie");
        cart.addItem("moleskine");

        carts.store(cart).returnBody(false).retry(3).execute();

        final ShoppingCart fetchedCart = carts.fetch(cart).execute();

        assertNotNull(fetchedCart);
        assertEquals(cart.getUserId(), fetchedCart.getUserId());
        assertEquals(cart, fetchedCart);

        carts.delete(fetchedCart).rw(3).execute();

        Thread.sleep(500);

        assertNull(carts.fetch(userId).execute());
    }

    @Test public void storeDomainObjectWithoutKeyAnnotation() throws Exception {
        final String bucketName = UUID.randomUUID().toString() + "_carts";
        final String userId = UUID.randomUUID().toString();

        final Bucket carts = client.createBucket(bucketName).allowSiblings(true).execute();

        final LegacyCart cart = new LegacyCart();
        cart.setUserId(userId);

        cart.addItem("coffee");
        cart.addItem("fixie");
        cart.addItem("moleskine");

        try {
            carts.store(cart).returnBody(false).retry(3).execute();
            fail("Expected NoKeySpecifiedException");
        } catch (NoKeySpecifedException e) {
            // NO-OP
        }

        carts.store(userId, cart).returnBody(false).retry(3).execute();

        try {
            carts.fetch(cart).retry(3).execute();
            fail("Expected NoKeySpecifiedException");
        } catch (NoKeySpecifedException e) {
            // NO-OP
        }

        final LegacyCart fetchedCart = carts.fetch(userId, LegacyCart.class).execute();

        assertNotNull(fetchedCart);
        assertEquals(cart.getUserId(), fetchedCart.getUserId());
        assertEquals(cart, fetchedCart);

        try {
            carts.delete(cart).retry(3).execute();
            fail("Expected NoKeySpecifiedException");
        } catch (NoKeySpecifedException e) {
            // NO-OP
        }

        carts.delete(userId).rw(3).execute();

        Thread.sleep(500);

        assertNull(carts.fetch(userId).execute());
    }

    @Test public void listKeys() throws Exception {
        final Set<String> keys = new LinkedHashSet<String>();

        final String bucketName = UUID.randomUUID().toString();

        Bucket b = client.fetchBucket(bucketName).execute();

        for (int i = 65; i <= 90; i++) {
            String key = Character.toString((char) i);
            b.store(key, i).execute();
            keys.add(key);
        }

        for (String key : b.keys()) {
            assertTrue(keys.remove(key));
        }

        assertTrue(keys.isEmpty());
    }
}
