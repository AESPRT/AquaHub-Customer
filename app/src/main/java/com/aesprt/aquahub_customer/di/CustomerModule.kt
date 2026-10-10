package com.aesprt.aquahub_customer.di

import com.aesprt.aquahub_customer.data.auth.SupabaseAuthRepository
import com.aesprt.aquahub_customer.data.catalog.SupabaseCatalogRepository
import com.aesprt.aquahub_customer.data.local.CustomerDatabase
import com.aesprt.aquahub_customer.data.location.AndroidLocationRepository
import com.aesprt.aquahub_customer.data.order.SupabaseOrderRepository
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.LocationRepository
import com.aesprt.aquahub_customer.domain.OrderRepository
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val customerModule = module {
    single { CustomerDatabase.create(get()) }
    single { ThemePreferences(get()) }
    single { get<CustomerDatabase>().profileDao() }
    single { get<CustomerDatabase>().addressDao() }
    single<AuthRepository> { SupabaseAuthRepository(get(), get(), get(), get()) }
    single<CatalogRepository> { SupabaseCatalogRepository() }
    single<LocationRepository> { AndroidLocationRepository(get()) }
    single<OrderRepository> { SupabaseOrderRepository() }
    viewModel { CustomerViewModel(get(), get(), get(), get(), get(), get()) }
}
