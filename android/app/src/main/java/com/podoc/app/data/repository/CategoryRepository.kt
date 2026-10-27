package com.podoc.app.data.repository

import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.CreateCategoryRequest
import com.podoc.app.data.model.UpdateCategoryRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CategoryRepository(
    private val apiServiceProvider: () -> PoDocApiService?
) {
    private val _categoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    val categoriesFlow: StateFlow<List<Category>> = _categoriesFlow.asStateFlow()

    private fun getApi(): PoDocApiService =
        apiServiceProvider() ?: throw IllegalStateException("Chưa kết nối tới máy chủ API")

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val list = getApi().getCategories()
            _categoriesFlow.value = list
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCategory(name: String, icon: String, color: String, sortOrder: Int): Result<Category> {
        return try {
            val cat = getApi().createCategory(CreateCategoryRequest(name, icon, color, sortOrder))
            // Cập nhật lại danh mục để mọi màn hình đều có danh mục mới ngay lập tức
            getCategories()
            Result.success(cat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateCategory(id: Int, name: String?, icon: String?, color: String?, sortOrder: Int?): Result<Category> {
        return try {
            val cat = getApi().updateCategory(id, UpdateCategoryRequest(name, icon, color, sortOrder))
            getCategories()
            Result.success(cat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCategory(id: Int): Result<Unit> {
        return try {
            getApi().deleteCategory(id)
            getCategories()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
