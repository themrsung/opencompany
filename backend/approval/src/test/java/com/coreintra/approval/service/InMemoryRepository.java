package com.coreintra.approval.service;

import java.util.ArrayList;
import java.util.Collections;
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
 * A hand-written in-memory {@link JpaRepository} for service tests.
 *
 * <p>These tests exercise the application layer's own rules — snapshotting,
 * quorum, recall, the trail — and none of them is about SQL. Running them
 * against a database would make a fast unit suite slow and would test Hibernate
 * as well as the service, while running them against H2 would test neither
 * honestly (no H2-shaped truth, per the brief). The queries themselves are
 * verified against real PostgreSQL by the integration suite.
 *
 * <p>Query-by-Example and paging by {@link Sort} are unimplemented on purpose:
 * nothing in this layer uses them, and a plausible-looking approximation would
 * only mislead whoever added the first caller.
 */
abstract class InMemoryRepository<T, I> implements JpaRepository<T, I> {

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

    @Override
    public List<T> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return new PageImpl<T>(findAll());
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
    @Deprecated
    public void deleteInBatch(Iterable<T> entities) {
        deleteAll(entities);
    }

    @Override
    @Deprecated
    public T getOne(I id) {
        return rows.get(id);
    }

    @Override
    @Deprecated
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

    /** Unmodifiable snapshot, for assertions about what was written. */
    List<T> all() {
        return Collections.unmodifiableList(new ArrayList<T>(rows.values()));
    }
}
