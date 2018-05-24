package net.jadoth.storage.types;

import static net.jadoth.X.notNull;

import net.jadoth.X;
import net.jadoth.collections.ConstHashTable;
import net.jadoth.collections.types.XGettingSequence;
import net.jadoth.collections.types.XGettingTable;
import net.jadoth.swizzling.types.Swizzle;
import net.jadoth.swizzling.types.Swizzle.IdType;
import net.jadoth.typing.KeyValue;

public interface StorageIdAnalysis
{
	// (24.05.2018 TM)FIXME: set of occuring TypeIds
	
	public XGettingTable<Swizzle.IdType, Long> highestIdsPerType();


	public static StorageIdAnalysis Empty()
	{
		return new StorageIdAnalysis.Implementation(X.emptyTable());
	}

	public static StorageIdAnalysis New(final Long highestTid, final Long highestOid, final Long highestCid)
	{
		return new StorageIdAnalysis.Implementation(
			ConstHashTable.New(
				X.KeyValue(Swizzle.IdType.TID, highestTid),
				X.KeyValue(Swizzle.IdType.OID, highestOid),
				X.KeyValue(Swizzle.IdType.CID, highestCid)
			)
		);
	}

	public static StorageIdAnalysis New(final XGettingSequence<KeyValue<Swizzle.IdType, Long>> values)
	{
		return new StorageIdAnalysis.Implementation(ConstHashTable.New(notNull(values)));
	}

	public final class Implementation implements StorageIdAnalysis
	{
		final XGettingTable<Swizzle.IdType, Long> highestIdsPerType;

		Implementation(final XGettingTable<IdType, Long> highestIdsPerType)
		{
			super();
			this.highestIdsPerType = highestIdsPerType;
		}

		@Override
		public final XGettingTable<IdType, Long> highestIdsPerType()
		{
			return this.highestIdsPerType;
		}

	}

}
