package com.podoc.app.data.repository

import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.CreateCategoryRequest
import com.podoc.app.data.model.UpdateCategoryRequest

class CategoryRepository(
    private val apiServiceProvider: () -> PoDocApiService?
) {
    private fun getApi(): PoDocApiService =
        apiServiceProvider() ?: throw IllegalStateException("Chưa kết nối tới máy chủ API")

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val list = getApi().getCategories()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCategory(name: String, icon: String, color: String, sortOrder: Int): Result<Category> {
        return try {
            val cat = getApi().createCategory(CreateCategoryRequest(name, icon, color, sortOrder))
            Result.success(cat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateCategory(id: Int, name: String?, icon: String?, color: String?, sortOrder: Int?): Result<Category> {
        return try {
            val cat = getApi().updateCategory(id, UpdateCategoryRequest(name, icon, color, sortOrder))
            Result.success(cat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCategory(id: Int): Result<Unit> {
        return try {
            getApi().deleteCategory(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
