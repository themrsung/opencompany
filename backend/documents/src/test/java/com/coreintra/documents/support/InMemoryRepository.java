package com.coreintra.documents.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

/**
 * A map pretending to be a repository, for tests that are about a service's decisions
 * rather than about SQL.
 *
 * <p>The documents module cannot reach {@code DatabaseTestSupport} - it lives in the app
 * module's test sources - so these tests are unit-level by necessity. That bound is worth
 * stating: what is proved here is that the service decides correctly, not that the schema
 * accepts the result. The constraints in {@code V7__documents.sql} are the other half and
 * are exercised where the app module boots against real PostgreSQL.
 *
 * <p>Everything Spring Data offers but no service uses throws rather than returning an
 * empty answer. A fake that quietly answers "nothing found" turns a test of the wrong
 * method into a passing test.
 */
public abstract class InMemoryRepository<T, I> implements JpaRepository<T, I> {

    protected final Map<I, T> rows = new LinkedHashMap<I, T>();

    private final Function<T, I> identity;

    protected InMemoryRepository(Function<T, I> identity) {
        this.identity = identity;
    }

    protected I idOf(T entity) {
        return identity.apply(entity);
    }

    /** Every row, in insertion order. The base for a subclass's derived finders. */
    protected List<T> all() {
        return new ArrayList<T>(rows.values());
    }

    @Override
    public <S extends T> S save(S entity) {
        rows.put(idOf(entity), entity);
        return entity;
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<S>();
        for (S entity : entities) {
            saved.add(save(entity));
        }
        return saved;
    }

    @Override
    public Optional<T> findById(I id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean existsById(I id) {
        return rows.containsKey(id);
    }

    @Override
    public List<T> findAll() {
        return all();
    }

    @Override
    public List<T> findAllById(Iterable<I> ids) {
        List<T> found = new ArrayList<T>();
        for (I id : ids) {
            T row = rows.get(id);
            if (row != null) {
                found.add(row);
            }
        }
        return found;
    }

    @Override
    public long count() {
        return rows.size();
    }

    @Override
    public void deleteById(I id) {
        rows.remove(id);
    }

    @Override
    public void delete(T entity) {
        rows.remove(idOf(entity));
    }

    @Override
    public void deleteAllById(Iterable<? extends I> ids) {
        for (I id : ids) {
            rows.remove(id);
        }
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }

    @Override
    public List<T> findAll(Sort sort) {
        return all();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return new PageImpl<T>(all());
    }

    @Override
    public void flush() {
        // Nothing is buffered: save() is the write.
    }

    @Override
    public <S extends T> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<T> entities) {
        deleteAll(entities);
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<I> ids) {
        deleteAllById(ids);
    }

    @Override
    public void deleteAllInBatch() {
        deleteAll();
    }

    @Override
    @SuppressWarnings("deprecation")
    public T getOne(I id) {
        return required(id);
    }

    @Override
    @SuppressWarnings("deprecation")
    public T getById(I id) {
        return required(id);
    }

    @Override
    public T getReferenceById(I id) {
        return required(id);
    }

    @Override
    public <S extends T> Optional<S> findOne(Example<S> example) {
        throw unsupported("findOne(Example)");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example) {
        throw unsupported("findAll(Example)");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        throw unsupported("findAll(Example, Sort)");
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw unsupported("findAll(Example, Pageable)");
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        throw unsupported("count(Example)");
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        throw unsupported("exists(Example)");
    }

    @Override
    public <S extends T, R> R findBy(Example<S> example,
            Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw unsupported("findBy(Example, Function)");
    }

    /** Helper for subclasses: the first row matching a predicate, in insertion order. */
    protected Optional<T> first(java.util.function.Predicate<T> predicate) {
        for (T row : rows.values()) {
            if (predicate.test(row)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** Helper for subclasses: every row matching a predicate, in insertion order. */
    protected List<T> filter(java.util.function.Predicate<T> predicate) {
        List<T> found = new ArrayList<T>();
        for (T row : rows.values()) {
            if (predicate.test(row)) {
                found.add(row);
            }
        }
        return found;
    }

    protected static <E> List<E> listOf(Collection<E> items) {
        return new ArrayList<E>(items);
    }

    private T required(I id) {
        T row = rows.get(id);
        if (row == null) {
            throw new IllegalArgumentException("no row with id " + id);
        }
        return row;
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                method + " is not faked. Answering it with an empty result would turn a test of "
                + "the wrong method into a passing test.");
    }
}
