package dev.mentoring.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mentoring.orders.domain.MovementKind;
import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.domain.OrderPlacement;
import dev.mentoring.orders.domain.OrderStatus;
import dev.mentoring.orders.domain.PurchaseOutcome;
import dev.mentoring.orders.exception.OrderProblem;
import dev.mentoring.orders.repository.OrderRepository;
import dev.mentoring.orders.repository.ProductRepository;
import dev.mentoring.orders.repository.model.RecordedPurchase;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** 서비스의 입력 검사와 처리 순서 확인. 실제 커밋과 롤백은 HTTP/DB 검사에서 별도 확인. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    private static final long BUYER_ID = 10;
    private static final long REQUEST_ID = 20;
    private static final long PRODUCT_ID = 30;
    private static final String PURCHASE_KEY = "purchase-001";

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    private OrderService orderService;

    @BeforeEach
    void createService() {
        orderService = new OrderService(orderRepository, productRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidOrderDetails")
    void rejectsInvalidOrderBeforeAccessingRepositories(long productId, int quantity) {
        assertThatThrownBy(() -> orderService.placeOrder("alice", PURCHASE_KEY, productId, quantity))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason().name()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(orderRepository, productRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidCancellationIdBeforeAccessingRepository(long orderId) {
        assertThatThrownBy(() -> orderService.cancelOrder("alice", orderId))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason().name()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(orderRepository, productRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidReadIdBeforeAccessingRepository(long orderId) {
        assertThatThrownBy(() -> orderService.getOrder("alice", orderId))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason().name()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(orderRepository, productRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidPageSettings")
    void rejectsInvalidPageBeforeAccessingRepository(long beforeOrderId, int pageSize) {
        assertThatThrownBy(() -> orderService.listOrders("alice", beforeOrderId, pageSize))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason().name()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(orderRepository, productRepository);
    }

    @Test
    void newPurchaseStoresDebitOrderAndOutcomeInOrder() {
        var order = order(OrderStatus.ACCEPTED);
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2))
                .thenReturn(Optional.of(REQUEST_ID));
        when(orderRepository.tryDecreaseStock(PRODUCT_ID, 2)).thenReturn(true);
        when(orderRepository.insertOrder(REQUEST_ID, BUYER_ID, PRODUCT_ID, 2)).thenReturn(order);

        assertThat(orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isEqualTo(new OrderPlacement.Created(order));
        var sequence = inOrder(orderRepository, productRepository);
        sequence.verify(orderRepository).findBuyerId("alice");
        sequence.verify(orderRepository).findRecordedPurchase(BUYER_ID, PURCHASE_KEY);
        sequence.verify(productRepository).findProduct(PRODUCT_ID);
        sequence.verify(orderRepository).tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2);
        sequence.verify(orderRepository).tryDecreaseStock(PRODUCT_ID, 2);
        sequence.verify(orderRepository).insertOrder(REQUEST_ID, BUYER_ID, PRODUCT_ID, 2);
        sequence.verify(orderRepository).appendMovement(order, MovementKind.DEBIT, -2);
        sequence.verify(orderRepository).finishPurchase(REQUEST_ID, PurchaseOutcome.ACCEPTED);
    }

    @Test
    void replayReturnsCurrentCancelledOrderWithoutAnotherDebit() {
        var cancelled = order(OrderStatus.CANCELLED);
        stubRecordedPurchase(PurchaseOutcome.ACCEPTED, 2);
        when(orderRepository.findOrderByRequest(REQUEST_ID, BUYER_ID)).thenReturn(cancelled);

        assertThat(orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isEqualTo(new OrderPlacement.Replayed(cancelled));
        verify(orderRepository, never()).tryDecreaseStock(anyLong(), anyInt());
        verify(orderRepository, never()).appendMovement(any(), any(), anyInt());
    }

    @Test
    void replayRejectsChangedQuantityWithoutAnotherDebit() {
        stubRecordedPurchase(PurchaseOutcome.ACCEPTED, 3);

        assertThatThrownBy(() -> orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason()).isEqualTo(OrderProblem.Reason.IDEMPOTENCY_KEY_REUSED));
        verify(orderRepository, never()).tryDecreaseStock(anyLong(), anyInt());
        verifyNoInteractions(productRepository);
    }

    /** 최초 조회에 없던 요청도 INSERT 대기 중 다른 요청이 확정하면 다시 읽어 처리. */
    @Test
    void insertConflictUsesAFreshReadInsteadOfTheInitialEmptyResult() {
        var accepted = order(OrderStatus.ACCEPTED);
        var recorded = new RecordedPurchase(REQUEST_ID, PRODUCT_ID, 2, PurchaseOutcome.ACCEPTED);
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.findRecordedPurchase(BUYER_ID, PURCHASE_KEY))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(recorded));
        when(orderRepository.tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2))
                .thenReturn(Optional.empty());
        when(orderRepository.findOrderByRequest(REQUEST_ID, BUYER_ID)).thenReturn(accepted);

        assertThat(orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isEqualTo(new OrderPlacement.Replayed(accepted));

        var sequence = inOrder(orderRepository, productRepository);
        sequence.verify(orderRepository).findBuyerId("alice");
        sequence.verify(orderRepository).findRecordedPurchase(BUYER_ID, PURCHASE_KEY);
        sequence.verify(productRepository).findProduct(PRODUCT_ID);
        sequence.verify(orderRepository).tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2);
        sequence.verify(orderRepository).findRecordedPurchase(BUYER_ID, PURCHASE_KEY);
        sequence.verify(orderRepository).findOrderByRequest(REQUEST_ID, BUYER_ID);
        verify(orderRepository, never()).tryDecreaseStock(anyLong(), anyInt());
    }

    @Test
    void missingRecordAfterAnInsertConflictIsNotTreatedAsANewPurchase() {
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason()).isEqualTo(OrderProblem.Reason.INVARIANT_VIOLATION));
        verify(orderRepository, never()).tryDecreaseStock(anyLong(), anyInt());
    }

    @Test
    void shortageReturnsAnOutcomeInsteadOfThrowingBeforeCommit() {
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2))
                .thenReturn(Optional.of(REQUEST_ID));
        when(orderRepository.tryDecreaseStock(PRODUCT_ID, 2)).thenReturn(false);

        assertThat(orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isInstanceOf(OrderPlacement.OutOfStock.class);
        verify(orderRepository).finishPurchase(REQUEST_ID, PurchaseOutcome.OUT_OF_STOCK);
        verify(orderRepository, never()).insertOrder(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void recordedShortageDoesNotTryStockAgain() {
        stubRecordedPurchase(PurchaseOutcome.OUT_OF_STOCK, 2);

        assertThat(orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2))
                .isInstanceOf(OrderPlacement.OutOfStock.class);
        verify(orderRepository, never()).tryDecreaseStock(anyLong(), anyInt());
    }

    @Test
    void cancellationRestoresOnlyTheStoredQuantity() {
        var cancelled = order(OrderStatus.CANCELLED);
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryCancelOwnedOrder(cancelled.id(), BUYER_ID)).thenReturn(Optional.of(cancelled));

        assertThat(orderService.cancelOrder("alice", cancelled.id())).isEqualTo(cancelled);
        var sequence = inOrder(orderRepository);
        sequence.verify(orderRepository).findBuyerId("alice");
        sequence.verify(orderRepository).tryCancelOwnedOrder(cancelled.id(), BUYER_ID);
        sequence.verify(orderRepository).restoreStock(cancelled);
        sequence.verify(orderRepository).appendMovement(cancelled, MovementKind.RESTORE, 2);
    }

    @Test
    void repeatedCancellationDoesNotRestoreAgain() {
        var cancelled = order(OrderStatus.CANCELLED);
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryCancelOwnedOrder(cancelled.id(), BUYER_ID)).thenReturn(Optional.empty());
        when(orderRepository.findOwnedOrder(cancelled.id(), BUYER_ID)).thenReturn(cancelled);

        assertThat(orderService.cancelOrder("alice", cancelled.id())).isEqualTo(cancelled);
        verify(orderRepository, never()).restoreStock(any());
        verify(orderRepository, never()).appendMovement(any(), any(), anyInt());
    }

    @Test
    void insertFailureStopsHistoryAndOutcomeWrites() {
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.tryRegisterPurchase(BUYER_ID, PURCHASE_KEY, PRODUCT_ID, 2))
                .thenReturn(Optional.of(REQUEST_ID));
        when(orderRepository.tryDecreaseStock(PRODUCT_ID, 2)).thenReturn(true);
        var failure = new DataAccessResourceFailureException("Test storage failure");
        when(orderRepository.insertOrder(REQUEST_ID, BUYER_ID, PRODUCT_ID, 2)).thenThrow(failure);

        assertThatThrownBy(() -> orderService.placeOrder("alice", PURCHASE_KEY, PRODUCT_ID, 2)).isSameAs(failure);
        verify(orderRepository, never()).appendMovement(any(), any(), anyInt());
        verify(orderRepository, never()).finishPurchase(anyLong(), any());
    }

    private void stubRecordedPurchase(PurchaseOutcome outcome, int recordedQuantity) {
        when(orderRepository.findBuyerId("alice")).thenReturn(BUYER_ID);
        when(orderRepository.findRecordedPurchase(BUYER_ID, PURCHASE_KEY))
                .thenReturn(Optional.of(new RecordedPurchase(REQUEST_ID, PRODUCT_ID, recordedQuantity, outcome)));
    }

    private static Order order(OrderStatus status) {
        Instant createdAt = Instant.parse("2026-11-10T00:00:00Z");
        return new Order(40, PRODUCT_ID, 2, status, createdAt,
                status == OrderStatus.CANCELLED ? createdAt.plusSeconds(60) : null);
    }

    private static Stream<Arguments> invalidOrderDetails() {
        return Stream.of(Arguments.of(0L, 1), Arguments.of(-1L, 1),
                Arguments.of(PRODUCT_ID, 0), Arguments.of(PRODUCT_ID, -1));
    }

    private static Stream<Arguments> invalidPageSettings() {
        return Stream.of(Arguments.of(0L, 20), Arguments.of(-1L, 20),
                Arguments.of(Long.MAX_VALUE, 0), Arguments.of(Long.MAX_VALUE, 101));
    }
}
