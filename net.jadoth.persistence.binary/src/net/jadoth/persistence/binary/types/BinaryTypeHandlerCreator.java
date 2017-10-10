package net.jadoth.persistence.binary.types;

import static net.jadoth.Jadoth.notNull;

import java.lang.reflect.Field;

import net.jadoth.collections.types.XGettingEnum;
import net.jadoth.persistence.binary.internal.BinaryHandlerNativeArrayObject;
import net.jadoth.persistence.types.PersistenceFieldLengthResolver;
import net.jadoth.persistence.types.PersistenceTypeAnalyzer;
import net.jadoth.persistence.types.PersistenceTypeHandler;
import net.jadoth.persistence.types.PersistenceTypeHandlerCreator;

public interface BinaryTypeHandlerCreator extends PersistenceTypeHandlerCreator<Binary>
{
	public static BinaryTypeHandlerCreator.Implementation New(
		final PersistenceTypeAnalyzer        typeAnalyzer  ,
		final PersistenceFieldLengthResolver lengthResolver
	)
	{
		return new BinaryTypeHandlerCreator.Implementation(
			notNull(typeAnalyzer)  ,
			notNull(lengthResolver)
		);
	}
	
	public final class Implementation
	extends PersistenceTypeHandlerCreator.AbstractImplementation<Binary>
	implements BinaryTypeHandlerCreator
	{
		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////
		
		Implementation(
			final PersistenceTypeAnalyzer        typeAnalyzer  ,
			final PersistenceFieldLengthResolver lengthResolver
		)
		{
			super(typeAnalyzer, lengthResolver);
			
		}
		
		
		
		///////////////////////////////////////////////////////////////////////////
		// methods //
		////////////
				
		@Override
		protected <T> PersistenceTypeHandler<Binary, T> createTypeHandlerArray(final Class<T> type)
		{
			// array types can never change and therefore can never have obsolete types.
			return new BinaryHandlerNativeArrayObject<>(type);
		}
		
		@Override
		protected <T> PersistenceTypeHandler<Binary, T> createTypeHandlerReflective(
			final Class<T>                       type             ,
			final XGettingEnum<Field>            persistableFields,
			final PersistenceFieldLengthResolver lengthResolver
		)
		{
			if(type.isEnum())
			{
				/* (09.06.2017 TM)TODO: enum BinaryHandler special case implementation once completed
				 * (10.06.2017 TM)NOTE: not sure if handling enums (constants) in an entity graph
				 * makes sense in the first place. The whole enum concept (the identity of an instance depending
				 *  on the name and/or the order of the field referencing it) is just too wacky for an entity graph.
				 * Use enums for logic, if you must, but keep them out of proper entity graphs.
				 */
//				return this.createEnumHandler(type, 0, persistableFields, this.lengthResolver);
			}

			// default implementation simply always uses a blank memory instantiator
			return new BinaryHandlerGeneric<>(
				type                                           ,
				0                                              , // typeId gets initialized later
				BinaryPersistence.blankMemoryInstantiator(type),
				persistableFields                              ,
				lengthResolver
			);
		}
		
		@SuppressWarnings("unchecked") // required generics crazy sh*t tinkering
		<T, E extends Enum<E>> PersistenceTypeHandler<Binary, T> createEnumHandler(
			final Class<?>                       type          ,
			final long                           tid           ,
			final XGettingEnum<Field>            allFields     ,
			final PersistenceFieldLengthResolver lengthResolver
		)
		{
			return (PersistenceTypeHandler<Binary, T>)new BinaryHandlerEnum<>(
				(Class<E>)type     ,
				tid                ,
				allFields          ,
				lengthResolver
			);
		}
		
	}
	
}
