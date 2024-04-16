package co.chainring.integrationtests.testutils

import co.chainring.sequencer.proto.ResetRequest
import co.chainring.sequencer.proto.SequencerResponse
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import co.chainring.core.sequencer.SequencerClient as CoreSequencerClient

class TestSequencerClient : CoreSequencerClient(), CloseableResource {
    suspend fun reset(): SequencerResponse {
        return stub.reset(ResetRequest.getDefaultInstance()).sequencerResponse
    }

    override fun close() {
        channel.shutdownNow()
    }
}
