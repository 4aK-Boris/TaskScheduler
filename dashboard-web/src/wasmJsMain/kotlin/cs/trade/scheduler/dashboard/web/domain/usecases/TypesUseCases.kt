package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.shared.dto.TypePauseDto

public class ListPausedTypesUseCase(private val repo: TypesRepository) {
    public suspend operator fun invoke(): Result<List<TypePauseDto>> = runCatching { repo.listPaused() }
}

public class ListKnownTypesUseCase(private val repo: TypesRepository) {
    public suspend operator fun invoke(): Result<List<String>> = runCatching { repo.listKnown() }
}

public class PauseTypeUseCase(private val repo: TypesRepository) {
    public suspend operator fun invoke(payloadType: String, reason: String?): Result<Boolean> =
        runCatching { repo.pause(payloadType, reason) }
}

public class UnpauseTypeUseCase(private val repo: TypesRepository) {
    public suspend operator fun invoke(payloadType: String): Result<Boolean> =
        runCatching { repo.unpause(payloadType) }
}
