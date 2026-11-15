// The search by meaning, checked on the computer: the same core the app runs (novelo_sense.h), the
// same prompts, cut to the same size. It prints, for each search, where the note it should find
// came, and writes the vectors of these made-up notes and searches for the app's tests.
//
//   tools/sense-check/build.sh <llama.cpp> <build dir>   (once)
//   <build dir>/sense-check <model.gguf> [fixtures.txt]
//
// Nothing here is a real note of hers: the notes below are made up for the check.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "novelo_sense.h"

namespace {

const char * QUERY = "task: search result | query: ";

std::string doc(const std::string & title, const std::string & text) {
    return "title: " + (title.empty() ? std::string("none") : title) + " | text: " + text;
}

struct Note { std::string title, text; };
struct Search { std::string q; int want; };

const std::vector<Note> NOTES = {
    {"Telefone do encanador", "Seu Zé, (11) 98765-4321, conserta vazamento e cano"},
    {"Senha do wifi da casa da minha mãe", "rede CasaMae, senha girassol2020"},
    {"Comprar ração do Cooper", "a de filhote, saco de 10 kg"},
    {"Reunión con el contador", "llevar los recibos de octubre y la factura de luz"},
    {"Dentist appointment", "Dr. Silva, Tuesday 3pm, cleaning and x-ray"},
    {"Ideia pro aniversário da Alma", "festa no parque com bolo de chocolate e balões azuis"},
    {"Contrato de aluguel", "vence em março, reajuste pelo IGP-M, falar com a imobiliária"},
    {"Treino da semana", "segunda corrida 5 km, quarta musculação, sexta natação"},
    {"none", "Hoje fui à praia com a Alma, o mar estava calmo e vimos o pôr do sol"},
    {"Vacina do Cooper", "antirrábica em dezembro, levar a carteirinha"},
};

const std::vector<Search> SEARCHES = {
    {"hay algún número de reparos", 0},
    {"plumber phone", 0},
    {"contraseña internet mamá", 1},
    {"comida do cachorro", 2},
    {"impuestos papeles", 3},
    {"ir ao dentista", 4},
    {"festa de criança", 5},
    {"apartamento renta", 6},
    {"academia exercícios", 7},
    {"dia na praia", 8},
    {"veterinário", 9},
};

std::vector<float> cut(const std::vector<float> & v, size_t dims) {
    std::vector<float> o(v.begin(), v.begin() + (long) std::min(dims, v.size()));
    double n = 0;
    for (float x : o) n += (double) x * x;
    n = std::sqrt(n);
    if (n > 0) for (float & x : o) x = (float) (x / n);
    return o;
}

float dot(const std::vector<float> & a, const std::vector<float> & b) {
    double s = 0;
    for (size_t i = 0; i < a.size(); i++) s += (double) a[i] * b[i];
    return (float) s;
}

} // namespace

int main(int argc, char ** argv) {
    if (argc < 2) { fprintf(stderr, "use: %s <model.gguf> [fixtures.txt]\n", argv[0]); return 2; }
    llama_log_set([](ggml_log_level level, const char * text, void *) { if (level >= GGML_LOG_LEVEL_ERROR) fputs(text, stderr); }, nullptr);
    novelo_sense::Sense * s = novelo_sense::load(argv[1], 2, 512);
    if (!s) { fprintf(stderr, "load failed\n"); return 1; }
    printf("n_embd %d\n", s->n_embd);
    const size_t dims = 512;

    std::vector<std::vector<float>> notes, searches;
    std::vector<float> raw;
    auto t0 = std::chrono::steady_clock::now();
    for (const auto & n : NOTES) {
        if (!novelo_sense::embed(s, doc(n.title == "none" ? "" : n.title, n.text), raw)) { fprintf(stderr, "embed failed\n"); return 1; }
        notes.push_back(cut(raw, dims));
    }
    auto t1 = std::chrono::steady_clock::now();
    for (const auto & q : SEARCHES) {
        if (!novelo_sense::embed(s, QUERY + q.q, raw)) { fprintf(stderr, "embed failed\n"); return 1; }
        searches.push_back(cut(raw, dims));
    }
    auto t2 = std::chrono::steady_clock::now();
    printf("ms per note %.1f, per search %.1f\n",
        std::chrono::duration<double, std::milli>(t1 - t0).count() / NOTES.size(),
        std::chrono::duration<double, std::milli>(t2 - t1).count() / SEARCHES.size());

    int first = 0;
    for (size_t i = 0; i < SEARCHES.size(); i++) {
        std::vector<std::pair<float, int>> r;
        for (size_t j = 0; j < notes.size(); j++) r.push_back({dot(searches[i], notes[j]), (int) j});
        std::sort(r.begin(), r.end(), [](auto & a, auto & b) { return a.first > b.first; });
        int rank = 0;
        while (r[rank].second != SEARCHES[i].want) rank++;
        if (rank == 0) first++;
        printf("%-32s want #%d at rank %d (cos %.3f; best %.3f, next %.3f)\n", SEARCHES[i].q.c_str(), SEARCHES[i].want, rank + 1,
            dot(searches[i], notes[SEARCHES[i].want]), r[0].first, r[1].first);
    }
    printf("first: %d of %zu\n", first, SEARCHES.size());

    // The made-up notes and searches as the app's tests read them: kind, index, then the 512 numbers.
    if (argc > 2) {
        FILE * f = fopen(argv[2], "w");
        if (!f) return 1;
        auto put = [&](const char * kind, size_t i, const std::vector<float> & v) {
            fprintf(f, "%s %zu", kind, i);
            for (float x : v) fprintf(f, " %.6f", x);
            fputc('\n', f);
        };
        for (size_t i = 0; i < notes.size(); i++) put("note", i, notes[i]);
        for (size_t i = 0; i < searches.size(); i++) put("search", i, searches[i]);
        fclose(f);
    }
    novelo_sense::free_sense(s);
    return first * 10 >= (int) SEARCHES.size() * 8 ? 0 : 3;
}
