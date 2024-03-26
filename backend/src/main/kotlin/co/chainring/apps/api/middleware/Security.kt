package co.chainring.apps.api.middleware

import co.chainring.core.model.Address
import org.http4k.core.Request

val Request.principal: Address
    get() = Address("0xb6De2e85F3d5E3B87780EF62a21bfEC01997b038") // mock
