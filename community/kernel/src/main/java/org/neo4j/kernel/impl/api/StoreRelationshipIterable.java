/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongIterable;
import org.neo4j.function.primitive.PrimitiveIntPredicate;
import org.neo4j.graphdb.Direction;
import org.neo4j.kernel.api.EntityType;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.impl.store.InvalidRecordException;
import org.neo4j.kernel.impl.store.NeoStore;
import org.neo4j.kernel.impl.store.RelationshipGroupStore;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;

/**
 * Low level {@link PrimitiveLongIterable} for iterating over relationship chains, both sparse and dense.
 * Goes directly to {@link RelationshipStore} and {@link RelationshipGroupStore} for loading its data.
 */
public class StoreRelationshipIterable implements PrimitiveLongIterable
{
    private final RelationshipStore relStore;
    private final RelationshipGroupStore groupStore;
    private final NodeRecord node;
    private final PrimitiveIntPredicate type;
    private final Direction direction;

    public StoreRelationshipIterable( NeoStore neoStore, long nodeId, PrimitiveIntPredicate type, Direction direction )
            throws EntityNotFoundException
    {
        this.type = type;
        this.direction = direction;
        this.relStore = neoStore.getRelationshipStore();
        this.groupStore = neoStore.getRelationshipGroupStore();
        this.node = neoStore.getNodeStore().loadRecord( nodeId, null );
        if ( node == null )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }
    }

    private static long followRelationshipChain( long nodeId, RelationshipRecord relRecord )
    {
        if ( relRecord.getFirstNode() == nodeId )
        {
            return relRecord.getFirstNextRel();
        }
        else if ( relRecord.getSecondNode() == nodeId )
        {
            return relRecord.getSecondNextRel();
        }

        throw new InvalidRecordException( "While loading relationships for Node[" + nodeId +
                "] a Relationship[" + relRecord.getId() + "] was encountered that had startNode: " +
                relRecord.getFirstNode() + " and endNode: " + relRecord.getSecondNode() +
                ", i.e. which had neither start nor end node as the node we're loading relationships for" );
    }

    @Override
    public StoreRelationshipIterator iterator()
    {
        if ( node.isDense() )
        {
            return new DenseIterator( node, groupStore, relStore, type, direction );
        }
        return new SparseIterator( node, relStore, type, direction );
    }

    public static abstract class StoreRelationshipIterator extends PrimitiveLongCollections.PrimitiveLongBaseIterator
    {
        protected final RelationshipStore relationshipStore;
        protected final PrimitiveIntPredicate type;
        protected final Direction direction;
        protected RelationshipRecord relationship;

        private StoreRelationshipIterator( RelationshipStore relationshipStore,
            PrimitiveIntPredicate type, Direction direction )
        {
            this.relationshipStore = relationshipStore;
            this.type = type;
            this.direction = direction;
        }

        public RelationshipRecord record()
        {
            return relationship;
        }

        protected boolean directionMatches( long nodeId, RelationshipRecord relationship )
        {
            switch ( direction )
            {
            case BOTH: return true;
            case OUTGOING: return relationship.getFirstNode() == nodeId;
            case INCOMING: return relationship.getSecondNode() == nodeId;
            default: throw new IllegalArgumentException( "Unknown direction " + direction );
            }
        }
    }

    private static class SparseIterator extends StoreRelationshipIterator
    {
        private final long nodeId;
        private long nextRelId;

        SparseIterator( NodeRecord nodeRecord, RelationshipStore relationshipStore,
                PrimitiveIntPredicate type, Direction direction )
        {
            super( relationshipStore, type, direction );
            this.nodeId = nodeRecord.getId();
            this.nextRelId = nodeRecord.getNextRel();
        }

        @Override
        protected boolean fetchNext()
        {
            while ( nextRelId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                relationship = relationshipStore.getRecord( nextRelId );
                try
                {
                    // Filter by type and direction
                    if ( type.accept( relationship.getType() ) && directionMatches( nodeId, relationship ) )
                    {
                        return next( nextRelId );
                    }
                }
                finally
                {
                    // Follow the relationship pointer to the next relationship
                    nextRelId = followRelationshipChain( nodeId, relationship );
                }
            }
            return false;
        }
    }

    private static class DenseIterator extends StoreRelationshipIterator
    {
        private final long nodeId;
        private final RelationshipGroupStore groupStore;
        private RelationshipGroupRecord groupRecord;
        private int groupChainIndex;
        private long nextRelId;

        DenseIterator( NodeRecord nodeRecord, RelationshipGroupStore groupStore,
                RelationshipStore relationshipStore, PrimitiveIntPredicate type, Direction direction )
        {
            super( relationshipStore, type, direction );
            this.groupStore = groupStore;
            this.nodeId = nodeRecord.getId();
            // Apparently returns null if !inUse
            this.groupRecord = groupStore.getRecord( nodeRecord.getNextRel() );
            this.nextRelId = nextChainStart();
        }

        private long nextChainStart()
        {
            while ( groupRecord != null )
            {
                if ( type.accept( groupRecord.getType() ) )
                {
                    // Go to the next chain (direction) within this group
                    while ( groupChainIndex < GROUP_CHAINS.length )
                    {
                        GroupChain groupChain = GROUP_CHAINS[groupChainIndex++];
                        long chainStart = groupChain.chainStart( groupRecord );
                        if ( chainStart != Record.NO_NEXT_RELATIONSHIP.intValue() &&
                                (direction == Direction.BOTH || groupChain.matchesDirection( direction ) ) )
                        {
                            return chainStart;
                        }
                    }
                }

                // Go to the next group
                groupRecord = groupRecord.getNext() != Record.NO_NEXT_RELATIONSHIP.intValue() ?
                        groupStore.getRecord( groupRecord.getNext() ) : null;
                groupChainIndex = 0;
            }
            return Record.NO_NEXT_RELATIONSHIP.intValue();
        }

        @Override
        protected boolean fetchNext()
        {
            while ( nextRelId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                relationship = relationshipStore.getRecord( nextRelId );
                try
                {
                    return next( nextRelId );
                }
                finally
                {
                    // Follow the relationship pointer to the next relationship
                    nextRelId = followRelationshipChain( nodeId, relationship );
                    if ( nextRelId == Record.NO_NEXT_RELATIONSHIP.intValue() )
                    {
                        // End of chain, try the next chain
                        nextRelId = nextChainStart();
                        // Potentially end of all chains here, and that's fine, we'll exit below
                    }
                }
            }
            return false;
        }
    }

    private static enum GroupChain
    {
        OUT
        {
            @Override
            long chainStart( RelationshipGroupRecord groupRecord )
            {
                return groupRecord.getFirstOut();
            }

            @Override
            boolean matchesDirection( Direction direction )
            {
                return direction == Direction.OUTGOING;
            }
        },
        IN
        {
            @Override
            long chainStart( RelationshipGroupRecord groupRecord )
            {
                return groupRecord.getFirstIn();
            }

            @Override
            boolean matchesDirection( Direction direction )
            {
                return direction == Direction.INCOMING;
            }
        },
        LOOP
        {
            @Override
            long chainStart( RelationshipGroupRecord groupRecord )
            {
                return groupRecord.getFirstLoop();
            }

            @Override
            boolean matchesDirection( Direction direction )
            {
                return true;
            }
        };

        abstract long chainStart( RelationshipGroupRecord groupRecord );

        abstract boolean matchesDirection( Direction direction );
    }

    private static final GroupChain[] GROUP_CHAINS = GroupChain.values();
}
