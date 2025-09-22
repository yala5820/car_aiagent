package com.hirain.aiagent.data

import kotlinx.coroutines.flow.Flow

interface CardRepository {

    fun getCardFunctions(): Flow<List<String>>

    fun getCards(): Flow<List<CardInfo>>
}