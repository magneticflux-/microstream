package one.microstream.persistence.types;

import static one.microstream.X.notNull;

import java.util.function.Consumer;

import one.microstream.collections.HashMapIdObject;
import one.microstream.collections.MiniMap;
import one.microstream.persistence.exceptions.PersistenceExceptionConsistency;
import one.microstream.persistence.exceptions.PersistenceExceptionTypeHandlerConsistencyConflictedType;
import one.microstream.persistence.exceptions.PersistenceExceptionTypeHandlerConsistencyConflictedTypeId;
import one.microstream.reflect.XReflect;

public interface PersistenceTypeHandlerRegistry<M>
extends PersistenceTypeHandlerLookup<M>, PersistenceTypeRegistry, PersistenceTypeHandlerIterable<M>
{
	public boolean registerTypeHandler(PersistenceTypeHandler<M, ?> typeHandler);
	
	public boolean registerLegacyTypeHandler(PersistenceLegacyTypeHandler<M, ?> legacyTypeHandler);
	
	
	

	public static <M> PersistenceTypeHandlerRegistry.Default<M> New(
		final PersistenceTypeRegistry typeRegistry
	)
	{
		return new PersistenceTypeHandlerRegistry.Default<>(
			notNull(typeRegistry)
		);
	}

	public final class Default<M> implements PersistenceTypeHandlerRegistry<M>
	{
		///////////////////////////////////////////////////////////////////////////
		// instance fields //
		////////////////////

		private final PersistenceTypeRegistry typeRegistry;

		private final MiniMap<Class<?>, PersistenceTypeHandler<M, ?>> handlersByType   = new MiniMap<>();
		private final HashMapIdObject<PersistenceTypeHandler<M, ?>>   handlersByTypeId = HashMapIdObject.New();



		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////

		Default(final PersistenceTypeRegistry typeRegistry)
		{
			super();
			this.typeRegistry = typeRegistry;
		}



		///////////////////////////////////////////////////////////////////////////
		// methods //
		////////////

		@Override
		public long lookupTypeId(final Class<?> type)
		{
			return this.typeRegistry.lookupTypeId(type);
		}

		@Override
		public <T> Class<T> lookupType(final long typeId)
		{
			return this.typeRegistry.lookupType(typeId);
		}

		@SuppressWarnings("unchecked") // cast type safety guaranteed by management logic
		@Override
		public <T> PersistenceTypeHandler<M, T> lookupTypeHandler(final Class<T> type)
		{
			return (PersistenceTypeHandler<M, T>)this.handlersByType.get(type);
		}

		@Override
		public PersistenceTypeHandler<M, ?> lookupTypeHandler(final long typeId)
		{
			return this.handlersByTypeId.get(typeId);
		}

		@Override
		public <T> PersistenceTypeHandler<M, T> lookupTypeHandler(final T instance)
		{
			// standard registry does not consider actual objects
			return this.lookupTypeHandler(XReflect.getClass(instance));
		}
		
		@Override
		public boolean validateTypeMapping(final long typeId, final Class<?> type) throws PersistenceExceptionConsistency
		{
			return this.typeRegistry.validateTypeMapping(typeId, type);
		}
		
		@Override
		public boolean validateTypeMappings(final Iterable<? extends PersistenceTypeLink> mappings)
			throws PersistenceExceptionConsistency
		{
			return this.typeRegistry.validateTypeMappings(mappings);
		}

		@Override
		public boolean registerType(final long tid, final Class<?> type) throws PersistenceExceptionConsistency
		{
			return this.typeRegistry.registerType(tid, type);
		}
		
		@Override
		public boolean registerTypes(final Iterable<? extends PersistenceTypeLink> types)
			throws PersistenceExceptionConsistency
		{
			return this.typeRegistry.registerTypes(types);
		}

		@Override
		public boolean registerTypeHandler(final PersistenceTypeHandler<M, ?> typeHandler)
		{
			synchronized(this.handlersByType)
			{
				final Class<?> type = typeHandler.type();
				final long     tid  = typeHandler.typeId();
				this.typeRegistry.registerType(tid, type); // first ensure consistency of tid<->type combination

				// check if handler is already registered for type
				PersistenceTypeHandler<M, ?> actualHandler;
				if((actualHandler = this.handlersByType.get(type)) != null)
				{
					if(actualHandler != typeHandler)
					{
						throw new PersistenceExceptionTypeHandlerConsistencyConflictedType(type, actualHandler, typeHandler);
					}
					// else: fall through to tid check
				}
				// else: handler is not registered yet, proceed with tid check

				// check if a handler is already registered for the same tid
				if(this.synchCheckByTypeId(typeHandler))
				{
					// redundant registering attempt, abort.
					return false;
				}
				// else: handler, tid, type combination is neither registered nor inconsistent, so register handler.

				// register new bidirectional assignment
				// note: basic type<->tid registration already happened above if necessary
				this.putMapping(typeHandler);
				
				return true;
			}
		}
		
		private boolean synchCheckByTypeId(final PersistenceTypeHandler<M, ?> typeHandler)
		{
			final PersistenceTypeHandler<M, ?> actualHandler;
			if((actualHandler = this.handlersByTypeId.get(typeHandler.typeId())) != null)
			{
				if(actualHandler != typeHandler)
				{
					throw new PersistenceExceptionTypeHandlerConsistencyConflictedTypeId(
						typeHandler.typeId(),
						actualHandler,
						typeHandler
					);
				}
				// else: handler is already consistently registered.
				return true;
			}
			
			return false;
		}
		

		@Override
		public boolean registerLegacyTypeHandler(final PersistenceLegacyTypeHandler<M, ?> legacyTypeHandler)
		{
			synchronized(this.handlersByType)
			{
				// check if a handler is already registered for the same tid
				if(this.synchCheckByTypeId(legacyTypeHandler))
				{
					// redundant registering attempt, abort.
					return false;
				}
				
				// no registration by type, just by typeId. This is a one-way translation helper for lookups by TID.
				this.handlersByTypeId.put(legacyTypeHandler.typeId(), legacyTypeHandler);
				
				return true;
			}
		}

		private void putMapping(final PersistenceTypeHandler<M, ?> typeHandler)
		{
			this.handlersByType.put(typeHandler.type(), typeHandler);
			this.handlersByTypeId.put(typeHandler.typeId(), typeHandler);
		}

		public void clear()
		{
			synchronized(this.handlersByType)
			{
				this.handlersByType.clear();
				this.handlersByTypeId.clear();
			}
		}

		@Override
		public <C extends Consumer<? super PersistenceTypeHandler<M, ?>>> C iterateTypeHandlers(
			final C iterator
		)
		{
			synchronized(this.handlersByType)
			{
				this.handlersByType.iterateValues(iterator);
			}
			return iterator;
		}
		
		@Override
		public <C extends Consumer<? super PersistenceLegacyTypeHandler<M, ?>>> C iterateLegacyTypeHandlers(
			final C iterator
		)
		{
			synchronized(this.handlersByType)
			{
				this.handlersByTypeId.iterateValues(th ->
				{
					if(th instanceof PersistenceLegacyTypeHandler)
					{
						iterator.accept((PersistenceLegacyTypeHandler<M, ?>)th);
					}
				});
			}
			
			return iterator;
		}
		
		@Override
		public <C extends Consumer<? super PersistenceTypeHandler<M, ?>>> C iterateAllTypeHandlers(final C iterator)
		{
			synchronized(this.handlersByType)
			{
				this.handlersByTypeId.iterateValues(iterator);
			}
			
			return iterator;
		}
	}

}
