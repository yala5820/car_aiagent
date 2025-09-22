package com.hirain.aiagent.data

import com.hirain.aiagent.data.local.LocalCardDataProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CardRepositoryImpl: CardRepository {

    override fun getCardFunctions(): Flow<List<String>> = flow {
        emit(LocalCardDataProvider.allFunctions)
    }

    override fun getCards(): Flow<List<CardInfo>> = flow {
        emit(LocalCardDataProvider.allCards)
    }
}