package net.jadoth.persistence.types;

import static java.lang.System.identityHashCode;
import static net.jadoth.X.KeyValue;
import static net.jadoth.X.notNull;

import java.lang.ref.WeakReference;

import net.jadoth.X;
import net.jadoth.collections.EqHashTable;
import net.jadoth.collections.XSort;
import net.jadoth.collections.types.XGettingTable;
import net.jadoth.hashing.HashStatisticsBucketBased;
import net.jadoth.hashing.Hashing;
import net.jadoth.math.XMath;
import net.jadoth.persistence.exceptions.PersistenceExceptionConsistencyObject;
import net.jadoth.typing.KeyValue;


public final class ObjectRegistry2 implements PersistenceObjectRegistry
{
	/* Notes:
	 * - all methods prefixed with "synch" are only called from inside a synchronized or another "synch" method.
	 * - all bucket arrays are effectively immutable, which makes lookups (storing) very fast.
	 * - the quad-bucket array storage is memory-inefficient for low density, but very efficient for high density.
	 * - sadly, WeakReferences occupy a lot of memory and there is no alternative to them for weakly referencing.
	 */
			
	///////////////////////////////////////////////////////////////////////////
	// static methods //
	///////////////////
		
	public static final float defaultHashDensity()
	{
		// below that, the overhead for the quad-bucket-arrays does not pay off.
		return 8.0f;
	}
	
	
	public static ObjectRegistry2 New()
	{
		return New(Default.defaultHashDensity());
	}
	
	public static ObjectRegistry2 New(final float desiredHashDensity)
	{
		return New(
			desiredHashDensity,
			(int)Math.ceil(desiredHashDensity / 2)
		);
	}
	
	public static ObjectRegistry2 New(
		final float desiredHashDensity  ,
		final int   bucketLengthInitialAndIncrease
	)
	{
		return New(
			desiredHashDensity            ,
			bucketLengthInitialAndIncrease,
			bucketLengthInitialAndIncrease
		);
	}
	
	public static ObjectRegistry2 New(
		final float desiredHashDensity  ,
		final int   bucketLengthInitial ,
		final int   bucketLengthIncrease
	)
	{
		// there is no point in supporting a desired initial capacity when there's a capacity low bound for the size.
		
		return new ObjectRegistry2()
			.internalSetHashConfiguration(
				Hashing.hashDensity(desiredHashDensity),
				XMath.positive(bucketLengthInitial),
				XMath.positive(bucketLengthIncrease)
			)
			.internalReset()
		;
	}
	
	
	
	///////////////////////////////////////////////////////////////////////////
	// instance fields //
	////////////////////
	
	private long[][] oidHashedOidKeysTable;
	private Item[][] oidHashedRefValsTable;
	private Item[][] refHashedRefKeysTable;
	private long[][] refHashedOidValsTable;
	
	/**
	 * A measurement of how "dense" entries are packed in the hashing structure.<br>
	 * Higher density means less memory consumption but also lower performance.<br>
	 * See {@link #minimumHashDensity()}.
	 */
	private float hashDensity;
	
	/**
	 * The current length of the hash table master arrays. All four have always the same length.
	 */
	private int hashLength;
	
	/**
	 * Due to hashing arithmetic, a hash range is always in the form of 2^n - 1, or in shift-writing: (1<<n) - 1.
	 */
	private int hashRange;
		
	/**
	 * The amount of containable entries before a rebuild to increase the storage is required.
	 */
	private long capacityHigh;
	
	/**
	 * The amount of containable entries before a rebuild to decrease the storage is required.
	 */
	private long capacityLow;
	
	/**
	 * The total entry count (including existnig orphan entries)
	 */
	private long size;
	
	private int bucketLengthInitial;
	
	private int bucketLengthIncrease;
	
	private EqHashTable<Long, Object> constantsHotRegistry = EqHashTable.New();
	private Object[]                  constantsColdStorageObjects  ;
	private long[]                    constantsColdStorageObjectIds;
	
	
	
	///////////////////////////////////////////////////////////////////////////
	// constructors //
	/////////////////
	
	ObjectRegistry2()
	{
		super();
	}
	
	
	
	///////////////////////////////////////////////////////////////////////////
	// methods //
	////////////
	
	final ObjectRegistry2 internalReset()
	{
		// staring low makes no performance difference in the long run.
		return this.internalReset(1);
	}
	
	final ObjectRegistry2 internalReset(final int hashLength)
	{
		this.oidHashedOidKeysTable = new long[hashLength][];
		this.oidHashedRefValsTable = new Item[hashLength][];
		this.refHashedRefKeysTable = new Item[hashLength][];
		this.refHashedOidValsTable = new long[hashLength][];
		
		this.hashLength = hashLength;
		this.hashRange  = hashLength - 1;
		
		this.internalUpdateCapacities();
		
		this.size = 0;
		
		return this;
	}
	
	final ObjectRegistry2 internalSetHashDensity(final float hashDensity)
	{
		this.hashDensity = hashDensity;
		
		return this;
	}
	
	final ObjectRegistry2 internalSetHashConfiguration(
		final float hashDensity         ,
		final int   bucketLengthInitial ,
		final int   bucketLengthIncrease
		
	)
	{
		this.hashDensity          = hashDensity         ;
		this.bucketLengthInitial  = bucketLengthInitial ;
		this.bucketLengthIncrease = bucketLengthIncrease;
		
		return this;
	}
	
	private void internalUpdateCapacities()
	{
		this.capacityHigh = (long)(this.hashLength * this.hashDensity);
		this.capacityLow  = this.hashLength == 1
			? 0
			: (long)(this.hashLength / 2 * this.hashDensity)
		;
	}
	
	private int hash(final long objectId)
	{
		/* (27.11.2018 TM)TODO: test and comment hashing performance
		 * - hash(){ (int)objectId & this.hashRange
		 * - hash(){ (int)(objectId & this.hashRange)
		 * - hash(){ (int)(objectId & this.hashRangeLong)
		 * - inlined (int)objectId & this.hashRange
		 * - inlined (int)(objectId & this.hashRange)
		 * - inlined (int)(objectId & this.hashRangeLong)
		 * 
		 * Also cover rehashByObjectId()
		 */
		
		// an sign bit accidentally caused by the cast is irrelevant since the hash range will always suppress it.
		return (int)objectId & this.hashRange;
	}
	
	private int hash(final Object object)
	{
		/* (27.11.2018 TM)TODO: test and comment hashing performance
		 * - hash(){ System.identityHashCode(object) & this.hashRange
		 * - inlined System.identityHashCode(object) & this.hashRange
		 */
		
		// an sign bit accidentally caused by the cast is irrelevant since the hash range will always suppress it.
		return System.identityHashCode(object) & this.hashRange;
	}
	
	
	
	@Override
	public final synchronized long size()
	{
		return this.size;
	}
	
	@Override
	public final synchronized boolean isEmpty()
	{
		return this.size == 0;
	}
	
	@Override
	public final synchronized int hashRange()
	{
		return this.hashRange;
	}
	
	@Override
	public final synchronized float hashDensity()
	{
		return this.hashDensity;
	}
		
	@Override
	public final synchronized ObjectRegistry2 setHashDensity(final float hashDensity)
	{
		this.internalSetHashDensity(Hashing.hashDensity(hashDensity));
		this.internalUpdateCapacities();
		this.synchCheckForRebuild();
		
		return this;
	}
	
	@Override
	public final synchronized long capacity()
	{
		return this.capacityHigh;
	}
	
	
	
	@Override
	public final long lookupObjectId(final Object object)
	{
		notNull(object);
		
		final Item[] refHashedRefKeys;
		final long[] refHashedOidVals;
		synchronized(this)
		{
			// both must be queried under protection of the same lock to guarantee consistency.
			refHashedRefKeys = this.refHashedRefKeysTable[this.hash(object)];
			if(refHashedRefKeys == null)
			{
				return Persistence.nullId();
			}
			
			refHashedOidVals = this.refHashedOidValsTable[this.hash(object)];
		}
		
		// the array should be a concurrency-safe thread-local stack copy, but honestly not exactely sure about it.
		for(int i = 0; i < refHashedRefKeys.length; i++)
		{
			// the first null terminates the bucket's entries
			if(refHashedRefKeys[i] == null)
			{
				break;
			}
			
			// can be null for orphan entries
			if(refHashedRefKeys[i].get() == object)
			{
				return refHashedOidVals[i];
			}
		}
		
		// since null can never be contained, returning the null-id signals a miss.
		return Persistence.nullId();
	}
	
	@Override
	public final Object lookupObject(final long objectId)
	{
		final long[] oidHashedOidKeys;
		final Item[] oidHashedRefVals;
		synchronized(this)
		{
			// both must be queried under protection of the same lock to guarantee consistency.
			oidHashedOidKeys = this.oidHashedOidKeysTable[this.hash(objectId)];
			if(oidHashedOidKeys == null)
			{
				return null;
			}
			
			oidHashedRefVals = this.oidHashedRefValsTable[this.hash(objectId)];
		}

		// the array should be a concurrency-safe thread-local stack copy, but honestly not exactely sure about it.
		for(int i = 0; i < oidHashedOidKeys.length; i++)
		{
			// the first 0 terminates the bucket's entries
			if(oidHashedOidKeys[i] == 0L)
			{
				break;
			}
			
			if(oidHashedOidKeys[i] == objectId)
			{
				return oidHashedRefVals[i].get();
			}
		}
		
		// since null can never be contained, returning the null signals a miss.
		return null;
	}
	
	@Override
	public final boolean containsObjectId(final long objectId)
	{
		final long[] oidHashedOidKeys;
		synchronized(this)
		{
			// must be queried under protection of a lock to guarantee consistency.
			oidHashedOidKeys = this.oidHashedOidKeysTable[this.hash(objectId)];
			if(oidHashedOidKeys == null)
			{
				return false;
			}
		}

		// the array should be a concurrency-safe thread-local stack copy, but honestly not exactely sure about it.
		for(int i = 0; i < oidHashedOidKeys.length; i++)
		{
			// the first 0 terminates the bucket's entries
			if(oidHashedOidKeys[i] == 0L)
			{
				break;
			}
			
			if(oidHashedOidKeys[i] == objectId)
			{
				return true;
			}
		}
		
		// signal objectId not found.
		return false;
	}
	
	
	
	@Override
	public final synchronized <A extends PersistenceAcceptor> A iterateEntries(final A acceptor)
	{
		// iterating everything has so many bucket accesses that a total lock is the only viable thing to do.
		
		final long[][] oidHashedOidKeysTable = this.oidHashedOidKeysTable;
		final Item[][] oidHashedRefValsTable = this.oidHashedRefValsTable;
		
		final int oidHashedOidKeysLength = oidHashedOidKeysTable.length;
		
		for(int h = 0; h < oidHashedOidKeysLength; h++)
		{
			final long[] oidHashedOidKeys = oidHashedOidKeysTable[h];
			if(oidHashedOidKeys == null)
			{
				continue;
			}
			
			final Item[] oidHashedRefVals = oidHashedRefValsTable[h];
			
			for(int i = 0; i < oidHashedOidKeys.length; i++)
			{
				// the first null terminates the bucket's entries
				if(oidHashedRefVals[i] == null)
				{
					break;
				}
				
				// might be an orphan item with a hollow weak reference.
				final Object instance = oidHashedRefVals[i].get();
				if(instance != null)
				{
					acceptor.accept(oidHashedOidKeys[i], instance);
				}
			}
		}
		
		return acceptor;
	}
		
	@Override
	public final synchronized boolean registerObject(final long objectId, final Object object)
	{
//		XDebug.println("(Size " + this.size + ") Registering " + objectId + " <-> " + XChars.systemString(object));
		
		// both branches must use the SAME Item instance to reduce memory consumption
		final Item newItem = this.synchAddPerObjectId(objectId, object, false);
		if(newItem == null)
		{
			return false;
		}
		
		// the second branch must be changed accordingly
		this.synchAddPerObject(objectId, newItem);
		
		// check for global rebuild after entries have changed (more or even fewer because of removed orphans)
		this.synchCheckForRebuild();
		
		return true;
	}
	
	@Override
	public final synchronized Object optionalRegisterObject(final long objectId, final Object object)
	{
		// both branches must use the SAME Item instance to reduce memory consumption
		final Item newItem = this.synchAddPerObjectId(objectId, object, true);
		if(newItem == null)
		{
			// null indicates that the object is already contained, so abort and return it.
			return object;
		}
		if(newItem.get() != object)
		{
			// a different object is already registered, so abort and return that.
			return newItem.get();
		}
		
		// the second branch must be changed accordingly
		this.synchAddPerObject(objectId, newItem);
		
		// check for global rebuild after entries have changed (more or even fewer because of removed orphans)
		this.synchCheckForRebuild();
		
		return object;
	}
	
	@Override
	public final synchronized boolean registerConstant(final long objectId, final Object constant)
	{
		if(!this.registerObject(objectId, constant))
		{
			return false;
		}
		
		this.synchEnsureConstantsHotRegistry().add(objectId, constant);
		
		return true;
	}
	
	private EqHashTable<Long, Object> synchEnsureConstantsHotRegistry()
	{
		if(this.constantsHotRegistry == null)
		{
			final EqHashTable<Long, Object> constantsHotRegistry = EqHashTable.New();
			final Object[] constantsObjects   = this.constantsColdStorageObjects  ;
			final long[]   constantsObjectIds = this.constantsColdStorageObjectIds;
			
			final int constantsLength = constantsObjects.length;
			for(int i = 0; i < constantsLength; i++)
			{
				constantsHotRegistry.add(constantsObjectIds[i], constantsObjects[i]);
			}
			
			this.constantsHotRegistry          = constantsHotRegistry;
			this.constantsColdStorageObjects   = null;
			this.constantsColdStorageObjectIds = null;
		}
		
		return this.constantsHotRegistry;
	}
	
	private void synchEnsureConstantColdStorage()
	{
		if(this.constantsColdStorageObjects != null)
		{
			return;
		}
		
		final EqHashTable<Long, Object> constantsHotRegistry = this.constantsHotRegistry;
		final int                       constantCount        = X.checkArrayRange(constantsHotRegistry.size());
		final Object[] constantsObjects   = new Object[constantCount];
		final long[]   constantsObjectIds = new long[constantCount];
		
		final int i = 0;
		for(final KeyValue<Long, Object> e : constantsHotRegistry)
		{
			constantsObjects[i] = e.value();
			constantsObjectIds[i] = e.key();
		}

		this.constantsHotRegistry          = null;
		this.constantsColdStorageObjects   = constantsObjects;
		this.constantsColdStorageObjectIds = constantsObjectIds;
	}
	
	@Override
	public final synchronized void cleanUp()
	{
		this.synchRebuild();
	}
	
	@Override
	public final synchronized void clear()
	{
		// reinitialize storage strucuture with a suitable size for the incoming constants.
		this.synchEnsureConstantColdStorage();
		
		final Object[] constantsObjects   = this.constantsColdStorageObjects  ;
		final long[]   constantsObjectIds = this.constantsColdStorageObjectIds;
		final int      constantsLength    = constantsObjects.length;
		final int      requiredHashLength = Hashing.calculateHashLength(constantsLength, this.hashDensity);
		
		this.internalReset(requiredHashLength);
		
		for(int i = 0; i < constantsLength; i++)
		{
			// NOT registerConstant() at this point!
			this.registerObject(constantsObjectIds[i], constantsObjects[i]);
		}
	}
	
	@Override
	public final synchronized void truncate()
	{
		// there is no point in keeping the old hash table arrays when there's a capacity low bound for the size.
		this.internalReset();
	}
		
	// removing //
	
	// (28.11.2018 TM)NOTE: removing is not required for normal operations, so its implementation is postponed.
	
	@Override
	public final synchronized boolean removeObjectById(final long id)
	{
		// TODO DefaultObjectRegistry#removeObjectById()
		throw new net.jadoth.meta.NotImplementedYetError();
	}
	
	@Override
	public final synchronized boolean removeObject(final Object object)
	{
		// TODO DefaultObjectRegistry#removeObject()
		throw new net.jadoth.meta.NotImplementedYetError();
	}
	
	@Override
	public final synchronized <P extends PersistencePredicate> P removeObjectsBy(final P filter)
	{
		// TODO DefaultObjectRegistry#removeObjectsBy()
		throw new net.jadoth.meta.NotImplementedYetError();
	}
	
	
	
	/* (27.11.2018 TM)TODO: smarter orphan management
	 * - fully check orphan count and rebuild if too high.
	 * - quick check orphan count (a few random samples asan estimate).
	 * - maybe implement a removeOrphans logic, but that could end up being almost as much work as a rebuild
	 */
	
	
	
	private Item synchAddPerObjectId(final long objectId, final Object object, final boolean optional)
	{
		final long[] oidKeys = this.oidHashedOidKeysTable[this.hash(objectId)];
		if(oidKeys == null)
		{
			return this.synchAddPerObjectIdInNewBucket(objectId, object);
		}
		
		final Item[] refVals = this.oidHashedRefValsTable[this.hash(objectId)];
		final int    length  = oidKeys.length;
		
		int t = 0; // target index in the new bucket arrays. Drags behind i for found orphan entries.
		for(int i = 0; i < length; i++)
		{
			if(oidKeys[i] == objectId)
			{
				if(refVals[i].get() == object)
				{
					// object is already registered, abort.
					return null;
				}
				
				final Object alreadyRegistered = refVals[i].get();
				if(alreadyRegistered != null)
				{
					if(optional)
					{
						return refVals[i];
					}
					throw new PersistenceExceptionConsistencyObject(objectId, alreadyRegistered, object);
				}
				
				// subject orphan entry, replace
				refVals[i] = new Item(object);
				// (28.11.2018 TM)FIXME: how to link from oid keys to ref keys? a link-int[] is needed.
			}
			
			// check for general orphan case
			if(refVals[i].get() == null)
			{
				// non-subject orphan entry, discard.
				continue;
			}
			
			// copy non-orphan non-subject entry to new bucket arrays
			newOidKeys[t] = oidKeys[i];
			newRefVals[t] = refVals[i];
			t++;
		}
		
		final Item objectItem = new Item(object);
		
		// if at least one orphan was found, the bucket arrays have to be rebuilt again.
		if(t < length)
		{
			this.addEntryPerObjectId(consolidate(newOidKeys, t), consolidate(newRefVals, t), objectId, objectItem);
			
			// size change: orphan count is subtracted, the new entry adds one.
			this.size = this.size - length + t + 1;
		}
		else
		{
			this.addEntryPerObjectId(newOidKeys, newRefVals, objectId, objectItem);
			
			// size change: no orphans, so just an increment.
			this.size++;
		}
		
		return objectItem;
	}
	
	private void synchAddPerObject(final long objectId, final Item item)
	{
		// this .get() is effectively strong referencing as the stack frames below reference the object itself.
		final int    hashIndex  = this.hash(item.get());
		final Item[] oldRefKeys = this.refHashedRefKeysTable[hashIndex];
		if(oldRefKeys == null)
		{
			this.synchAddPerObjectInNewBucket(objectId, item, hashIndex);
			return;
		}
		
		final long[] oldOidVals = this.refHashedOidValsTable[hashIndex];
		final int    oldLength  = oldOidVals.length;
		
		final Item[] newRefKeys = new Item[oldLength + 1];
		final long[] newOidVals = new long[oldLength + 1];
		
		int t = 0; // target index in the new bucket arrays. Drags behind i for found orphan entries.
		for(int i = 0; i < oldLength; i++)
		{
			if(oldOidVals[i] == objectId)
			{
				// can only be a subject orphan entry as the other cases would have been handled before entering here.
				continue;
			}
			
			// check for general orphan case
			if(oldRefKeys[i].get() == null)
			{
				// non-subject orphan entry, discard.
				continue;
			}
			
			// copy non-orphan non-subject entry to new bucket arrays
			newRefKeys[t] = oldRefKeys[i];
			newOidVals[t] = oldOidVals[i];
			t++;
		}
		
		// if at least orphan was found, the bucket arrays have to be rebuilt again.
		if(t < oldLength)
		{
			this.addEntryPerObject(consolidate(newRefKeys, t), consolidate(newOidVals, t), hashIndex, objectId, item);
			// size change already done by per-oid adding. Orphans not accounted for there are neglected here.
		}
		else
		{
			this.addEntryPerObject(newRefKeys, newOidVals, hashIndex, objectId, item);
			// size change already done by per-oid adding
		}
	}
	
	private Item synchAddPerObjectIdInNewBucket(final long objectId, final Object object)
	{
		final int refHashIndex = this.hash(object);
		final Item objectItem = new Item(object, refHashIndex);
		
		(this.oidHashedOidKeysTable[this.hash(objectId)] = new long[this.bucketLengthInitial])[0] = objectId;
		(this.oidHashedRefValsTable[this.hash(objectId)] = new Item[this.bucketLengthInitial])[0] = objectItem;
		
		final Item[] refBucket = this.refHashedRefKeysTable[refHashIndex];
		if(refBucket == null)
		{
			(this.refHashedRefKeysTable[this.hash(objectId)] = new Item[this.bucketLengthInitial])[0] = objectItem;
			(this.refHashedOidValsTable[this.hash(objectId)] = new long[this.bucketLengthInitial])[0] = objectId;
		}
		else
		{
			for(int i = 0; i < refBucket.length; i++)
			{
				if(refBucket[i] != null)
				{
					continue;
				}
				refBucket[i] = objectItem;
				this.refHashedOidValsTable[refHashIndex][i] = objectId;
			}
			// (28.11.2018 TM)FIXME: enlarge bucket arrays and add entry at the current length
		}
		
		this.size++;
		
		return objectItem;
	}
	
	private void synchAddPerObjectInNewBucket(final long objectId, final Item objectItem, final int hashIndex)
	{
		(this.refHashedRefKeysTable[hashIndex] = new Item[this.bucketLengthInitial])[0] = objectItem;
		(this.refHashedOidValsTable[hashIndex] = new long[this.bucketLengthInitial])[0] = objectId  ;
		// size change already done by per-oid adding
	}
					
	private void synchCheckForRebuild()
	{
		if(this.size > this.capacityHigh)
		{
//				XDebug.println("Increase required.");
			// increase required because the hash table became too small (or entries distribution too dense).
			this.synchRebuild();
			return;
		}
		
		if(this.size < this.capacityLow)
		{
//				XDebug.println("Decrease required.");
			// decrease required because the hash table became unnecessarily big. Redundant call for debugging.
			// (28.11.2018 TM)FIXME: /!\ DEBUG
//				this.synchRebuild();
			return;
		}
		
		// otherwise, no rebuild is required.
	}
	
	private void synchRebuild()
	{
		// locally cached old references / values.
		final int      oldHashLength   = this.hashLength;
		final long[][] oldOidKeysTable = this.oidHashedOidKeysTable;
		final Item[][] oldRefValsTable = this.oidHashedRefValsTable;
		
		// locally created new references / values.
		final int      newHashLength   = Hashing.calculateHashLength(this.size, this.hashDensity);
		final int      newHashRange    = newHashLength - 1;
		final long[][] newOidKeysTable = new long[newHashLength][];
		final Item[][] newRefValsTable = new Item[newHashLength][];
		final Item[][] newRefKeysTable = new Item[newHashLength][];
		final long[][] newOidValsTable = new long[newHashLength][];
		
		// this is also the only opportunity to recalculate the actual size
		long size = 0;
		
		// both new branches are populated from the per-oid-branch
		for(int h = 0; h < oldHashLength; h++)
		{
//				XDebug.println("Rebuild old hash table " + h);
			final long[] oldOidKeys = oldOidKeysTable[h];
			if(oldOidKeys == null)
			{
				continue;
			}
			
			final Item[] oldRefVals = oldRefValsTable[h];
			for(int i = 0; i < oldRefVals.length; i++)
			{
//					XDebug.println("Rebuild old hash table " + h + " -> " + i);
				if(oldRefVals[i].get() == null)
				{
//						XDebug.println("Orphan");
					continue;
				}
				
				final long oid = oldOidKeys[i];
				final Item ref = oldRefVals[i];
				populateByObjectId(newOidKeysTable, newRefValsTable, (int)oid & newHashRange, oid, ref);
				populateByObject(newRefKeysTable, newOidValsTable, identityHashCode(ref.get()) & newHashRange, oid, ref);
				size++;
				
//					XDebug.println("new Entry: " + size);
			}
		}
		
//			XDebug.println("Rebuild " + this.hashLength  + " -> " + newHashLength);
		
		// registry state gets switched over from old to new
		this.oidHashedOidKeysTable = newOidKeysTable;
		this.oidHashedRefValsTable = newRefValsTable;
		this.refHashedRefKeysTable = newRefKeysTable;
		this.refHashedOidValsTable = newOidValsTable;
		this.hashLength            = newHashLength  ;
		this.hashRange             = newHashRange   ;
		this.size                  = size           ;
		// hash density remains the same
		this.internalUpdateCapacities();
		
		// at some point, constant registration is completed, so an efficient storage form is preferable.
		this.synchEnsureConstantColdStorage();
	}
	
	private void addEntryPerObjectId(
		final long[] oidBucket ,
		final Item[] refBucket ,
		final long   objectId  ,
		final Item   objectItem
	)
	{
		addNewEntry(oidBucket, refBucket, objectId, objectItem);
		this.oidHashedOidKeysTable[this.hash(objectId)] = oidBucket;
		this.oidHashedRefValsTable[this.hash(objectId)] = refBucket;
	}
	
	private void addEntryPerObject(
		final Item[] refBucket ,
		final long[] oidBucket ,
		final int    hashIndex ,
		final long   objectId  ,
		final Item   objectItem
	)
	{
		addNewEntry(oidBucket, refBucket, objectId, objectItem);
		this.refHashedRefKeysTable[hashIndex] = refBucket;
		this.refHashedOidValsTable[hashIndex] = oidBucket;
	}

	
	
	private static void addNewEntry(
		final long[] oidBucket ,
		final Item[] refBucket ,
		final long   objectId  ,
		final Item   objectItem
	)
	{
		// a new entry is always at the last index
		oidBucket[oidBucket.length - 1] = objectId;
		refBucket[refBucket.length - 1] = objectItem;
	}
	
	private static long[] consolidate(final long[] array, final int elementCount)
	{
		final long[] newArray = new long[elementCount + 1];
		System.arraycopy(array, 0, newArray, 0, elementCount);
		return newArray;
	}
	
	private static Item[] consolidate(final Item[] array, final int elementCount)
	{
		final Item[] newArray = new Item[elementCount + 1];
		System.arraycopy(array, 0, newArray, 0, elementCount);
		return newArray;
	}
	
	private static void populateByObjectId(
		final long[][] oidKeysTable,
		final Item[][] refValsTable,
		final int      hashIndex   ,
		final long     oid         ,
		final Item     item
	)
	{
		oidKeysTable[hashIndex] = newOidsBucket(oidKeysTable[hashIndex], oid);
		refValsTable[hashIndex] = newRefsBucket(refValsTable[hashIndex], item);
	}
	
	private static void populateByObject(
		final Item[][] refKeysTable,
		final long[][] oidValsTable,
		final int      hashIndex   ,
		final long     oid         ,
		final Item     item
	)
	{
		refKeysTable[hashIndex] = newRefsBucket(refKeysTable[hashIndex], item);
		oidValsTable[hashIndex] = newOidsBucket(oidValsTable[hashIndex], oid);
	}
	
	private static long[] newOidsBucket(final long[] oids, final long oid)
	{
		if(oids == null)
		{
			return new long[]{oid};
		}
		
		final long[] newBucket = new long[oids.length + 1];
		System.arraycopy(oids, 0, newBucket, 0, oids.length);
		newBucket[newBucket.length - 1] = oid;
		return newBucket;
	}
	
	private static Item[] newRefsBucket(final Item[] refs, final Item item)
	{
		if(refs == null)
		{
			return new Item[]{item};
		}
		
		final Item[] newBucket = new Item[refs.length + 1];
		System.arraycopy(refs, 0, newBucket, 0, refs.length);
		newBucket[newBucket.length - 1] = item;
		return newBucket;
	}
	
	
	
	// HashStatistics logic //
	
	@Override
	public final synchronized XGettingTable<String, HashStatisticsBucketBased> createHashStatistics()
	{
		return EqHashTable.New(
			KeyValue("PerObjectIds", this.synchCreateHashStatisticsOids()),
			KeyValue("PerObjects", this.synchCreateHashStatisticsRefs())
		);
	}
	
	private HashStatisticsBucketBased synchCreateHashStatisticsOids()
	{
		final EqHashTable<Long, Long> distributionTable = EqHashTable.New();
		
		final long[][] oidHashedOidKeysTable = this.oidHashedOidKeysTable;
		final int oidHashedOidKeysLength = oidHashedOidKeysTable.length;
		for(int h = 0; h < oidHashedOidKeysLength; h++)
		{
			final long[] bucket = oidHashedOidKeysTable[h];
			final Long bucketLength = bucket == null ? null : (long)bucket.length;
			registerDistribution(distributionTable, bucketLength);
		}
		complete(distributionTable);
		
		return HashStatisticsBucketBased.New(
			this.hashLength                ,
			this.size                      ,
			this.hashDensity               ,
			distributionTable.keys().last(),
			distributionTable
		);
	}
	
	private HashStatisticsBucketBased synchCreateHashStatisticsRefs()
	{
		final EqHashTable<Long, Long> distributionTable = EqHashTable.New();

		final Item[][] refHashedRefKeysTable = this.refHashedRefKeysTable;
		final int refHashedRefKeysLength = refHashedRefKeysTable.length;
		for(int h = 0; h < refHashedRefKeysLength; h++)
		{
			final Item[] bucket = refHashedRefKeysTable[h];
			final Long bucketLength = bucket == null ? null : (long)bucket.length;
			registerDistribution(distributionTable, bucketLength);
		}
		complete(distributionTable);
		
		return HashStatisticsBucketBased.New(
			this.hashLength                ,
			this.size                      ,
			this.hashDensity               ,
			distributionTable.keys().last(),
			distributionTable
		);
	}
	
	private static void registerDistribution(final EqHashTable<Long, Long> distributionTable, final Long bucketLength)
	{
		// (28.11.2018 TM)NOTE: this is pretty ineffecient. Could be done much more efficient if required.
		final Long count = distributionTable.get(bucketLength);
		if(count == null)
		{
			distributionTable.put(bucketLength, 1L);
		}
		else
		{
			distributionTable.put(bucketLength, count + 1L);
		}
	}
	
	private static void complete(final EqHashTable<Long, Long> distributionTable)
	{
		distributionTable.keys().sort(XSort::compare);
		final Long highest = distributionTable.last().key();
		final Long zero = 0L;
		
		distributionTable.add(null, zero);
		for(long l = 0; l < highest; l++)
		{
			distributionTable.add(l, zero);
		}
		distributionTable.keys().sort(XSort::compare);
	}
	
	
	
	///////////////////////////////////////////////////////////////////////////
	// member types     //
	/////////////////////
	
	static final class Item extends WeakReference<Object>
	{
		int refHashIndex;
		
		Item(final Object referent, final int refHashIndex)
		{
			super(referent);
			this.refHashIndex = refHashIndex;
		}
	}
	
}