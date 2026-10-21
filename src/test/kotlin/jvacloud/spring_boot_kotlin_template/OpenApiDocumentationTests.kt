package jvacloud.spring_boot_kotlin_template

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertContains

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTests {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun `publishes the OpenAPI document`() {
		val body = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.response
			.contentAsString

		assertContains(body, "\"openapi\"")
		assertContains(body, "\"paths\"")
	}

}
