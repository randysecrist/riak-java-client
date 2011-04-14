package com.megacorp.commerce;

import java.util.ArrayList;
import java.util.Collection;

import com.basho.riak.newapi.cap.ConflictResolver;
import com.basho.riak.newapi.cap.UnresolvedConflictException;

/**
 * A simple example of a conflict resolver for the ShoppingCart domain type.
 * 
 * Merge the contents of any siblings, worse case is deletes get undone.
 * 
 * @author russell
 * 
 */
public final class MergeResolver implements ConflictResolver<ShoppingCart> {
   
    public ShoppingCart resolve(Collection<ShoppingCart> siblings) throws UnresolvedConflictException {
        String userId = null;
        final Collection<String> items = new ArrayList<String>();

        for (ShoppingCart c : siblings) {
            userId = c.getUserId();
            for (String item : c) {
                items.add(item);
            }
        }

        final ShoppingCart resolved = new ShoppingCart(userId);
        return resolved.addItems(items);
    }
}