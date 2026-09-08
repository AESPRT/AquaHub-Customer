package com.aesprt.aquahub_customer.di

import com.aesprt.aquahub_customer.data.auth.FirebaseAuthRepository
import com.aesprt.aquahub_customer.data.catalog.FirestoreCatalogRepository
import com.aesprt.aquahub_customer.data.local.CustomerDatabase
import com.aesprt.aquahub_customer.data.location.AndroidLocationRepository
import com.aesprt.aquahub_customer.data.order.FirebaseOrderRepository
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.LocationRepository
import com.aesprt.aquahub_customer.domain.OrderRepository
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val customerModule = module {
    single { CustomerDatabase.create(get()) }
    single { get<CustomerDatabase>().profileDao() }
    single { get<CustomerDatabase>().addressDao() }
    single<AuthRepository> { FirebaseAuthRepository(get(), get(), get()) }
    single<CatalogRepository> { FirestoreCatalogRepository(get()) }
    single<LocationRepository> { AndroidLocationRepository(get()) }
    single<OrderRepository> { FirebaseOrderRepository(get()) }
    viewModel { CustomerViewModel(get(), get(), get(), get(), get()) }
}
