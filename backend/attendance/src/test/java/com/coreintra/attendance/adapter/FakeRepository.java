package com.coreintra.attendance.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

/**
 * An in-memory {@link JpaRepository} for the adapter tests.
 *
 * <p>The adapters do two things worth testing without a database: they group
 * rows back into domain objects, and they refuse rather than guess when the rows
 * do not say enough. Neither is about SQL. What <em>is</em> about SQL — that the
 * derived query names resolve, that the columns exist, that the schema
 * validates — is proved by {@code ApprovalFlowIntegrationTest} against real
 * PostgreSQL, because only a real database can prove it.
 *
 * <p>Everything not used here throws. An approximation of query-by-example would
 * only mislead whoever adds the first caller.
 */
abstract class FakeRepository<T, I> implements JpaRepository<T, I> {

    final Map<I, T> rows = new LinkedHashMap<I, T>();

    abstract I idOf(T entity);

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
        return new ArrayList<T>(rows.values());
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

    /** Unmodifiable snapshot, for assertions about what was written. */
    List<T> all() {
        return Collections.unmodifiableList(new ArrayList<T>(rows.values()));
    }

    @Override
    public List<T> findAll(Sort sort) {
        throw new UnsupportedOperationException("sorting is done by the derived queries");
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        throw new UnsupportedOperationException("paging is not used by these adapters");
    }

    @Override
    public void flush() {
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
        return rows.get(id);
    }

    @Override
    @SuppressWarnings("deprecation")
    public T getById(I id) {
        return rows.get(id);
    }

    @Override
    public T getReferenceById(I id) {
        return rows.get(id);
    }

    @Override
    public <S extends T> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException("query by example is not used in this layer");
    }

    @Override
    public <S extends T, R> R findBy(Example<S> example,
            Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException("fluent queries are not used in this layer");
    }
}
