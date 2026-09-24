package com.yumi;

import com.yumi.shared.numbering.SequenceAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SequenceAllocatorTest {

    @Autowired
    SequenceAllocator allocator;

    @Test
    void allocatesMonotonicNumbersWithinTransaction() {
        var first = allocator.next("test-seq");
        var second = allocator.next("test-seq");
        assertThat(first).isGreaterThanOrEqualTo(1);
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void formatsFiveDigitBusinessNumber() {
        assertThat(SequenceAllocator.format("P", 7)).isEqualTo("P00007");
        assertThat(SequenceAllocator.format("E", 12345)).isEqualTo("E12345");
    }

    @Test
    void independentKeysDoNotInterfere() {
        var p = allocator.next("test-seq-p");
        var c = allocator.next("test-seq-c");
        assertThat(SequenceAllocator.format("P", p)).hasSize(6);
        assertThat(SequenceAllocator.format("C", c)).hasSize(6);
    }
}
